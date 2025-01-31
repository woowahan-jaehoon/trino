/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.trino.cost;

import com.google.common.base.VerifyException;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.trino.Session;
import io.trino.execution.warnings.WarningCollector;
import io.trino.security.AllowAllAccessControl;
import io.trino.spi.type.Type;
import io.trino.sql.PlannerContext;
import io.trino.sql.analyzer.ExpressionAnalyzer;
import io.trino.sql.analyzer.Scope;
import io.trino.sql.planner.ExpressionInterpreter;
import io.trino.sql.planner.LiteralEncoder;
import io.trino.sql.planner.LiteralInterpreter;
import io.trino.sql.planner.NoOpSymbolResolver;
import io.trino.sql.planner.Symbol;
import io.trino.sql.planner.TypeProvider;
import io.trino.sql.tree.AstVisitor;
import io.trino.sql.tree.BetweenPredicate;
import io.trino.sql.tree.BooleanLiteral;
import io.trino.sql.tree.ComparisonExpression;
import io.trino.sql.tree.Expression;
import io.trino.sql.tree.FunctionCall;
import io.trino.sql.tree.InListExpression;
import io.trino.sql.tree.InPredicate;
import io.trino.sql.tree.IsNotNullPredicate;
import io.trino.sql.tree.IsNullPredicate;
import io.trino.sql.tree.Literal;
import io.trino.sql.tree.LogicalExpression;
import io.trino.sql.tree.Node;
import io.trino.sql.tree.NodeRef;
import io.trino.sql.tree.NotExpression;
import io.trino.sql.tree.SymbolReference;

import javax.annotation.Nullable;
import javax.inject.Inject;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static io.trino.cost.ComparisonStatsCalculator.estimateExpressionToExpressionComparison;
import static io.trino.cost.ComparisonStatsCalculator.estimateExpressionToLiteralComparison;
import static io.trino.cost.PlanNodeStatsEstimateMath.addStatsAndSumDistinctValues;
import static io.trino.cost.PlanNodeStatsEstimateMath.capStats;
import static io.trino.cost.PlanNodeStatsEstimateMath.subtractSubsetStats;
import static io.trino.spi.statistics.StatsUtil.toStatsRepresentation;
import static io.trino.spi.type.BooleanType.BOOLEAN;
import static io.trino.sql.DynamicFilters.isDynamicFilter;
import static io.trino.sql.ExpressionUtils.and;
import static io.trino.sql.tree.ComparisonExpression.Operator.EQUAL;
import static io.trino.sql.tree.ComparisonExpression.Operator.GREATER_THAN_OR_EQUAL;
import static io.trino.sql.tree.ComparisonExpression.Operator.LESS_THAN_OR_EQUAL;
import static java.lang.Double.NaN;
import static java.lang.Double.isInfinite;
import static java.lang.Double.isNaN;
import static java.lang.Double.min;
import static java.lang.String.format;
import static java.util.Collections.emptyMap;
import static java.util.Objects.requireNonNull;

public class FilterStatsCalculator
{
    static final double UNKNOWN_FILTER_COEFFICIENT = 0.9;

    private final PlannerContext plannerContext;
    private final ScalarStatsCalculator scalarStatsCalculator;
    private final StatsNormalizer normalizer;

    @Inject
    public FilterStatsCalculator(PlannerContext plannerContext, ScalarStatsCalculator scalarStatsCalculator, StatsNormalizer normalizer)
    {
        this.plannerContext = requireNonNull(plannerContext, "plannerContext is null");
        this.scalarStatsCalculator = requireNonNull(scalarStatsCalculator, "scalarStatsCalculator is null");
        this.normalizer = requireNonNull(normalizer, "normalizer is null");
    }

    public PlanNodeStatsEstimate filterStats(
            PlanNodeStatsEstimate statsEstimate,
            Expression predicate,
            Session session,
            TypeProvider types)
    {
        Expression simplifiedExpression = simplifyExpression(session, predicate, types);
        return new FilterExpressionStatsCalculatingVisitor(statsEstimate, session, types)
                .process(simplifiedExpression);
    }

    private Expression simplifyExpression(Session session, Expression predicate, TypeProvider types)
    {
        // TODO reuse io.trino.sql.planner.iterative.rule.SimplifyExpressions.rewrite

        Map<NodeRef<Expression>, Type> expressionTypes = getExpressionTypes(session, predicate, types);
        ExpressionInterpreter interpreter = new ExpressionInterpreter(predicate, plannerContext, session, expressionTypes);
        Object value = interpreter.optimize(NoOpSymbolResolver.INSTANCE);

        if (value == null) {
            // Expression evaluates to SQL null, which in Filter is equivalent to false. This assumes the expression is a top-level expression (eg. not in NOT).
            value = false;
        }
        return new LiteralEncoder(plannerContext).toExpression(session, value, BOOLEAN);
    }

    private Map<NodeRef<Expression>, Type> getExpressionTypes(Session session, Expression expression, TypeProvider types)
    {
        ExpressionAnalyzer expressionAnalyzer = ExpressionAnalyzer.createWithoutSubqueries(
                plannerContext,
                new AllowAllAccessControl(),
                session,
                types,
                emptyMap(),
                node -> new IllegalStateException("Unexpected node: " + node),
                WarningCollector.NOOP,
                false);
        expressionAnalyzer.analyze(expression, Scope.create());
        return expressionAnalyzer.getExpressionTypes();
    }

    private class FilterExpressionStatsCalculatingVisitor
            extends AstVisitor<PlanNodeStatsEstimate, Void>
    {
        private final PlanNodeStatsEstimate input;
        private final Session session;
        private final TypeProvider types;

        FilterExpressionStatsCalculatingVisitor(PlanNodeStatsEstimate input, Session session, TypeProvider types)
        {
            this.input = input;
            this.session = session;
            this.types = types;
        }

        @Override
        public PlanNodeStatsEstimate process(Node node, @Nullable Void context)
        {
            return normalizer.normalize(super.process(node, context), types);
        }

        @Override
        protected PlanNodeStatsEstimate visitExpression(Expression node, Void context)
        {
            return PlanNodeStatsEstimate.unknown();
        }

        @Override
        protected PlanNodeStatsEstimate visitNotExpression(NotExpression node, Void context)
        {
            if (node.getValue() instanceof IsNullPredicate) {
                return process(new IsNotNullPredicate(((IsNullPredicate) node.getValue()).getValue()));
            }
            return subtractSubsetStats(input, process(node.getValue()));
        }

        @Override
        protected PlanNodeStatsEstimate visitLogicalExpression(LogicalExpression node, Void context)
        {
            switch (node.getOperator()) {
                case AND:
                    return estimateLogicalAnd(node.getTerms());
                case OR:
                    return estimateLogicalOr(node.getTerms());
            }
            throw new IllegalArgumentException("Unexpected binary operator: " + node.getOperator());
        }

        private PlanNodeStatsEstimate estimateLogicalAnd(List<Expression> terms)
        {
            // first try to estimate in the fair way
            PlanNodeStatsEstimate estimate = process(terms.get(0));
            if (!estimate.isOutputRowCountUnknown()) {
                for (int i = 1; i < terms.size(); i++) {
                    estimate = new FilterExpressionStatsCalculatingVisitor(estimate, session, types).process(terms.get(i));

                    if (estimate.isOutputRowCountUnknown()) {
                        break;
                    }
                }

                if (!estimate.isOutputRowCountUnknown()) {
                    return estimate;
                }
            }

            // If some of the filters cannot be estimated, take the smallest estimate.
            // Apply 0.9 filter factor as "unknown filter" factor.
            Optional<PlanNodeStatsEstimate> smallest = terms.stream()
                    .map(this::process)
                    .filter(termEstimate -> !termEstimate.isOutputRowCountUnknown())
                    .sorted(Comparator.comparingDouble(PlanNodeStatsEstimate::getOutputRowCount))
                    .findFirst();

            if (smallest.isEmpty()) {
                return PlanNodeStatsEstimate.unknown();
            }

            return smallest.get().mapOutputRowCount(rowCount -> rowCount * UNKNOWN_FILTER_COEFFICIENT);
        }

        private PlanNodeStatsEstimate estimateLogicalOr(List<Expression> terms)
        {
            PlanNodeStatsEstimate previous = process(terms.get(0));
            if (previous.isOutputRowCountUnknown()) {
                return PlanNodeStatsEstimate.unknown();
            }

            for (int i = 1; i < terms.size(); i++) {
                PlanNodeStatsEstimate current = process(terms.get(i));
                if (current.isOutputRowCountUnknown()) {
                    return PlanNodeStatsEstimate.unknown();
                }

                PlanNodeStatsEstimate andEstimate = new FilterExpressionStatsCalculatingVisitor(previous, session, types).process(terms.get(i));
                if (andEstimate.isOutputRowCountUnknown()) {
                    return PlanNodeStatsEstimate.unknown();
                }

                previous = capStats(
                        subtractSubsetStats(
                                addStatsAndSumDistinctValues(previous, current),
                                andEstimate),
                        input);
            }

            return previous;
        }

        @Override
        protected PlanNodeStatsEstimate visitBooleanLiteral(BooleanLiteral node, Void context)
        {
            if (node.getValue()) {
                return input;
            }

            PlanNodeStatsEstimate.Builder result = PlanNodeStatsEstimate.builder();
            result.setOutputRowCount(0.0);
            input.getSymbolsWithKnownStatistics().forEach(symbol -> result.addSymbolStatistics(symbol, SymbolStatsEstimate.zero()));
            return result.build();
        }

        @Override
        protected PlanNodeStatsEstimate visitIsNotNullPredicate(IsNotNullPredicate node, Void context)
        {
            if (node.getValue() instanceof SymbolReference) {
                Symbol symbol = Symbol.from(node.getValue());
                SymbolStatsEstimate symbolStats = input.getSymbolStatistics(symbol);
                PlanNodeStatsEstimate.Builder result = PlanNodeStatsEstimate.buildFrom(input);
                result.setOutputRowCount(input.getOutputRowCount() * (1 - symbolStats.getNullsFraction()));
                result.addSymbolStatistics(symbol, symbolStats.mapNullsFraction(x -> 0.0));
                return result.build();
            }
            return PlanNodeStatsEstimate.unknown();
        }

        @Override
        protected PlanNodeStatsEstimate visitIsNullPredicate(IsNullPredicate node, Void context)
        {
            if (node.getValue() instanceof SymbolReference) {
                Symbol symbol = Symbol.from(node.getValue());
                SymbolStatsEstimate symbolStats = input.getSymbolStatistics(symbol);
                PlanNodeStatsEstimate.Builder result = PlanNodeStatsEstimate.buildFrom(input);
                result.setOutputRowCount(input.getOutputRowCount() * symbolStats.getNullsFraction());
                result.addSymbolStatistics(symbol, SymbolStatsEstimate.builder()
                        .setNullsFraction(1.0)
                        .setLowValue(NaN)
                        .setHighValue(NaN)
                        .setDistinctValuesCount(0.0)
                        .build());
                return result.build();
            }
            return PlanNodeStatsEstimate.unknown();
        }

        @Override
        protected PlanNodeStatsEstimate visitBetweenPredicate(BetweenPredicate node, Void context)
        {
            if (!(node.getValue() instanceof SymbolReference)) {
                return PlanNodeStatsEstimate.unknown();
            }
            if (!getExpressionStats(node.getMin()).isSingleValue()) {
                return PlanNodeStatsEstimate.unknown();
            }
            if (!getExpressionStats(node.getMax()).isSingleValue()) {
                return PlanNodeStatsEstimate.unknown();
            }

            SymbolStatsEstimate valueStats = input.getSymbolStatistics(Symbol.from(node.getValue()));
            Expression lowerBound = new ComparisonExpression(GREATER_THAN_OR_EQUAL, node.getValue(), node.getMin());
            Expression upperBound = new ComparisonExpression(LESS_THAN_OR_EQUAL, node.getValue(), node.getMax());

            Expression transformed;
            if (isInfinite(valueStats.getLowValue())) {
                // We want to do heuristic cut (infinite range to finite range) ASAP and then do filtering on finite range.
                // We rely on 'and()' being processed left to right
                transformed = and(lowerBound, upperBound);
            }
            else {
                transformed = and(upperBound, lowerBound);
            }
            return process(transformed);
        }

        @Override
        protected PlanNodeStatsEstimate visitInPredicate(InPredicate node, Void context)
        {
            if (!(node.getValueList() instanceof InListExpression)) {
                return PlanNodeStatsEstimate.unknown();
            }

            InListExpression inList = (InListExpression) node.getValueList();
            ImmutableList<PlanNodeStatsEstimate> equalityEstimates = inList.getValues().stream()
                    .map(inValue -> process(new ComparisonExpression(EQUAL, node.getValue(), inValue)))
                    .collect(toImmutableList());

            if (equalityEstimates.stream().anyMatch(PlanNodeStatsEstimate::isOutputRowCountUnknown)) {
                return PlanNodeStatsEstimate.unknown();
            }

            PlanNodeStatsEstimate inEstimate = equalityEstimates.stream()
                    .reduce(PlanNodeStatsEstimateMath::addStatsAndSumDistinctValues)
                    .orElse(PlanNodeStatsEstimate.unknown());

            if (inEstimate.isOutputRowCountUnknown()) {
                return PlanNodeStatsEstimate.unknown();
            }

            SymbolStatsEstimate valueStats = getExpressionStats(node.getValue());
            if (valueStats.isUnknown()) {
                return PlanNodeStatsEstimate.unknown();
            }

            double notNullValuesBeforeIn = input.getOutputRowCount() * (1 - valueStats.getNullsFraction());

            PlanNodeStatsEstimate.Builder result = PlanNodeStatsEstimate.buildFrom(input);
            result.setOutputRowCount(min(inEstimate.getOutputRowCount(), notNullValuesBeforeIn));

            if (node.getValue() instanceof SymbolReference) {
                Symbol valueSymbol = Symbol.from(node.getValue());
                SymbolStatsEstimate newSymbolStats = inEstimate.getSymbolStatistics(valueSymbol)
                        .mapDistinctValuesCount(newDistinctValuesCount -> min(newDistinctValuesCount, valueStats.getDistinctValuesCount()));
                result.addSymbolStatistics(valueSymbol, newSymbolStats);
            }
            return result.build();
        }

        @Override
        protected PlanNodeStatsEstimate visitComparisonExpression(ComparisonExpression node, Void context)
        {
            ComparisonExpression.Operator operator = node.getOperator();
            Expression left = node.getLeft();
            Expression right = node.getRight();

            checkArgument(!(left instanceof Literal && right instanceof Literal), "Literal-to-literal not supported here, should be eliminated earlier");

            if (!(left instanceof SymbolReference) && right instanceof SymbolReference) {
                // normalize so that symbol is on the left
                return process(new ComparisonExpression(operator.flip(), right, left));
            }

            if (left instanceof Literal && !(right instanceof Literal)) {
                // normalize so that literal is on the right
                return process(new ComparisonExpression(operator.flip(), right, left));
            }

            if (left instanceof SymbolReference && left.equals(right)) {
                return process(new IsNotNullPredicate(left));
            }

            SymbolStatsEstimate leftStats = getExpressionStats(left);
            Optional<Symbol> leftSymbol = left instanceof SymbolReference ? Optional.of(Symbol.from(left)) : Optional.empty();
            if (right instanceof Literal) {
                OptionalDouble literal = doubleValueFromLiteral(getType(left), (Literal) right);
                return estimateExpressionToLiteralComparison(input, leftStats, leftSymbol, literal, operator);
            }

            SymbolStatsEstimate rightStats = getExpressionStats(right);
            if (rightStats.isSingleValue()) {
                OptionalDouble value = isNaN(rightStats.getLowValue()) ? OptionalDouble.empty() : OptionalDouble.of(rightStats.getLowValue());
                return estimateExpressionToLiteralComparison(input, leftStats, leftSymbol, value, operator);
            }

            Optional<Symbol> rightSymbol = right instanceof SymbolReference ? Optional.of(Symbol.from(right)) : Optional.empty();
            return estimateExpressionToExpressionComparison(input, leftStats, leftSymbol, rightStats, rightSymbol, operator);
        }

        @Override
        protected PlanNodeStatsEstimate visitFunctionCall(FunctionCall node, Void context)
        {
            if (isDynamicFilter(node)) {
                return process(BooleanLiteral.TRUE_LITERAL, context);
            }
            return PlanNodeStatsEstimate.unknown();
        }

        private Type getType(Expression expression)
        {
            if (expression instanceof SymbolReference) {
                Symbol symbol = Symbol.from(expression);
                return requireNonNull(types.get(symbol), () -> format("No type for symbol %s", symbol));
            }

            ExpressionAnalyzer expressionAnalyzer = ExpressionAnalyzer.createWithoutSubqueries(
                    plannerContext,
                    new AllowAllAccessControl(),
                    session,
                    types,
                    ImmutableMap.of(),
                    // At this stage, there should be no subqueries in the plan.
                    node -> new VerifyException("Unexpected subquery"),
                    WarningCollector.NOOP,
                    false);
            return expressionAnalyzer.analyze(expression, Scope.create());
        }

        private SymbolStatsEstimate getExpressionStats(Expression expression)
        {
            if (expression instanceof SymbolReference) {
                Symbol symbol = Symbol.from(expression);
                return requireNonNull(input.getSymbolStatistics(symbol), () -> format("No statistics for symbol %s", symbol));
            }
            return scalarStatsCalculator.calculate(expression, input, session, types);
        }

        private OptionalDouble doubleValueFromLiteral(Type type, Literal literal)
        {
            Object literalValue = LiteralInterpreter.evaluate(plannerContext, session, getExpressionTypes(session, literal, types), literal);
            return toStatsRepresentation(type, literalValue);
        }
    }
}
