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
package io.trino.sql.planner;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.trino.Session;
import io.trino.spi.expression.ConnectorExpression;
import io.trino.spi.type.Type;
import io.trino.sql.tree.ArithmeticBinaryExpression;
import io.trino.sql.tree.Expression;
import io.trino.sql.tree.FunctionCall;
import io.trino.sql.tree.LongLiteral;
import io.trino.sql.tree.NodeRef;
import io.trino.sql.tree.QualifiedName;
import io.trino.sql.tree.StringLiteral;
import io.trino.sql.tree.SubscriptExpression;
import io.trino.sql.tree.SymbolReference;
import io.trino.transaction.TransactionId;
import org.testng.annotations.Test;

import java.util.List;
import java.util.Map;

import static com.google.common.collect.Iterables.getOnlyElement;
import static io.trino.spi.type.BigintType.BIGINT;
import static io.trino.spi.type.DoubleType.DOUBLE;
import static io.trino.spi.type.IntegerType.INTEGER;
import static io.trino.spi.type.RowType.field;
import static io.trino.spi.type.RowType.rowType;
import static io.trino.spi.type.VarcharType.createVarcharType;
import static io.trino.sql.planner.ConnectorExpressionTranslator.translate;
import static io.trino.sql.planner.PartialTranslator.extractPartialTranslations;
import static io.trino.sql.planner.TestingPlannerContext.PLANNER_CONTEXT;
import static io.trino.sql.planner.TypeAnalyzer.createTestingTypeAnalyzer;
import static io.trino.sql.tree.ArithmeticBinaryExpression.Operator.ADD;
import static io.trino.testing.TestingSession.testSessionBuilder;
import static org.testng.Assert.assertEquals;

public class TestPartialTranslator
{
    private static final Session TEST_SESSION = testSessionBuilder()
            .setTransactionId(TransactionId.create())
            .build();
    private static final TypeAnalyzer TYPE_ANALYZER = createTestingTypeAnalyzer(PLANNER_CONTEXT);
    private static final TypeProvider TYPE_PROVIDER = TypeProvider.copyOf(ImmutableMap.<Symbol, Type>builder()
            .put(new Symbol("double_symbol_1"), DOUBLE)
            .put(new Symbol("double_symbol_2"), DOUBLE)
            .put(new Symbol("bigint_symbol_1"), BIGINT)
            .put(new Symbol("row_symbol_1"), rowType(field("int_symbol_1", INTEGER), field("varchar_symbol_1", createVarcharType(5))))
            .build());

    @Test
    public void testPartialTranslator()
    {
        Expression rowSymbolReference = new SymbolReference("row_symbol_1");
        Expression dereferenceExpression1 = new SubscriptExpression(rowSymbolReference, new LongLiteral("1"));
        Expression dereferenceExpression2 = new SubscriptExpression(rowSymbolReference, new LongLiteral("2"));
        Expression stringLiteral = new StringLiteral("abcd");
        Expression symbolReference1 = new SymbolReference("double_symbol_1");

        assertFullTranslation(symbolReference1);
        assertFullTranslation(dereferenceExpression1);
        assertFullTranslation(stringLiteral);

        Expression binaryExpression = new ArithmeticBinaryExpression(ADD, symbolReference1, dereferenceExpression1);
        assertPartialTranslation(binaryExpression, ImmutableList.of(symbolReference1, dereferenceExpression1));

        List<Expression> functionArguments = ImmutableList.of(stringLiteral, dereferenceExpression2);
        Expression functionCallExpression = new FunctionCall(QualifiedName.of("concat"), functionArguments);
        assertPartialTranslation(functionCallExpression, functionArguments);
    }

    private void assertPartialTranslation(Expression expression, List<Expression> subexpressions)
    {
        Map<NodeRef<Expression>, ConnectorExpression> translation = extractPartialTranslations(expression, TEST_SESSION, TYPE_ANALYZER, TYPE_PROVIDER);
        assertEquals(subexpressions.size(), translation.size());
        for (Expression subexpression : subexpressions) {
            assertEquals(translation.get(NodeRef.of(subexpression)), translate(TEST_SESSION, subexpression, TYPE_ANALYZER, TYPE_PROVIDER).get());
        }
    }

    private void assertFullTranslation(Expression expression)
    {
        Map<NodeRef<Expression>, ConnectorExpression> translation = extractPartialTranslations(expression, TEST_SESSION, TYPE_ANALYZER, TYPE_PROVIDER);
        assertEquals(getOnlyElement(translation.keySet()), NodeRef.of(expression));
        assertEquals(getOnlyElement(translation.values()), translate(TEST_SESSION, expression, TYPE_ANALYZER, TYPE_PROVIDER).get());
    }
}
