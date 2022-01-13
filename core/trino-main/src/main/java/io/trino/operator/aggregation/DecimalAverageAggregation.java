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
package io.trino.operator.aggregation;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import io.airlift.slice.Slice;
import io.trino.metadata.AggregationFunctionMetadata;
import io.trino.metadata.BoundSignature;
import io.trino.metadata.FunctionMetadata;
import io.trino.metadata.FunctionNullability;
import io.trino.metadata.Signature;
import io.trino.metadata.SqlAggregationFunction;
import io.trino.operator.aggregation.AggregationMetadata.AccumulatorStateDescriptor;
import io.trino.operator.aggregation.state.LongDecimalWithOverflowAndLongState;
import io.trino.operator.aggregation.state.LongDecimalWithOverflowAndLongStateFactory;
import io.trino.operator.aggregation.state.LongDecimalWithOverflowAndLongStateSerializer;
import io.trino.spi.block.Block;
import io.trino.spi.block.BlockBuilder;
import io.trino.spi.type.DecimalType;
import io.trino.spi.type.Decimals;
import io.trino.spi.type.Type;
import io.trino.spi.type.TypeSignature;

import java.lang.invoke.MethodHandle;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Optional;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.Iterables.getOnlyElement;
import static io.airlift.slice.SizeOf.SIZE_OF_LONG;
import static io.trino.metadata.FunctionKind.AGGREGATE;
import static io.trino.spi.type.Decimals.writeShortDecimal;
import static io.trino.spi.type.TypeSignatureParameter.typeVariable;
import static io.trino.spi.type.UnscaledDecimal128Arithmetic.SIGN_LONG_MASK;
import static io.trino.spi.type.UnscaledDecimal128Arithmetic.UNSCALED_DECIMAL_128_SLICE_LENGTH;
import static io.trino.spi.type.UnscaledDecimal128Arithmetic.addWithOverflow;
import static io.trino.spi.type.UnscaledDecimal128Arithmetic.divideRoundUp;
import static io.trino.spi.type.UnscaledDecimal128Arithmetic.unscaledDecimalToBigInteger;
import static io.trino.spi.type.UnscaledDecimal128Arithmetic.unscaledDecimalToUnscaledLong;
import static io.trino.spi.type.VarbinaryType.VARBINARY;
import static io.trino.util.Reflection.methodHandle;
import static java.math.BigDecimal.ROUND_HALF_UP;

public class DecimalAverageAggregation
        extends SqlAggregationFunction
{
    public static final DecimalAverageAggregation DECIMAL_AVERAGE_AGGREGATION = new DecimalAverageAggregation();

    private static final String NAME = "avg";
    private static final MethodHandle SHORT_DECIMAL_INPUT_FUNCTION = methodHandle(DecimalAverageAggregation.class, "inputShortDecimal", LongDecimalWithOverflowAndLongState.class, Block.class, int.class);
    private static final MethodHandle LONG_DECIMAL_INPUT_FUNCTION = methodHandle(DecimalAverageAggregation.class, "inputLongDecimal", LongDecimalWithOverflowAndLongState.class, Block.class, int.class);

    private static final MethodHandle SHORT_DECIMAL_OUTPUT_FUNCTION = methodHandle(DecimalAverageAggregation.class, "outputShortDecimal", DecimalType.class, LongDecimalWithOverflowAndLongState.class, BlockBuilder.class);
    private static final MethodHandle LONG_DECIMAL_OUTPUT_FUNCTION = methodHandle(DecimalAverageAggregation.class, "outputLongDecimal", DecimalType.class, LongDecimalWithOverflowAndLongState.class, BlockBuilder.class);

    private static final MethodHandle COMBINE_FUNCTION = methodHandle(DecimalAverageAggregation.class, "combine", LongDecimalWithOverflowAndLongState.class, LongDecimalWithOverflowAndLongState.class);

    private static final BigInteger TWO = new BigInteger("2");
    private static final BigInteger OVERFLOW_MULTIPLIER = TWO.shiftLeft(UNSCALED_DECIMAL_128_SLICE_LENGTH * 8 - 2);

    public DecimalAverageAggregation()
    {
        super(
                new FunctionMetadata(
                        new Signature(
                                NAME,
                                new TypeSignature("decimal", typeVariable("p"), typeVariable("s")),
                                ImmutableList.of(new TypeSignature("decimal", typeVariable("p"), typeVariable("s")))),
                        new FunctionNullability(true, ImmutableList.of(false)),
                        false,
                        true,
                        "Calculates the average value",
                        AGGREGATE),
                new AggregationFunctionMetadata(
                        false,
                        VARBINARY.getTypeSignature()));
    }

    @Override
    public AggregationMetadata specialize(BoundSignature boundSignature)
    {
        Type type = getOnlyElement(boundSignature.getArgumentTypes());
        checkArgument(type instanceof DecimalType, "type must be Decimal");
        MethodHandle inputFunction;
        MethodHandle outputFunction;
        Class<LongDecimalWithOverflowAndLongState> stateInterface = LongDecimalWithOverflowAndLongState.class;
        LongDecimalWithOverflowAndLongStateSerializer stateSerializer = new LongDecimalWithOverflowAndLongStateSerializer();

        if (((DecimalType) type).isShort()) {
            inputFunction = SHORT_DECIMAL_INPUT_FUNCTION;
            outputFunction = SHORT_DECIMAL_OUTPUT_FUNCTION;
        }
        else {
            inputFunction = LONG_DECIMAL_INPUT_FUNCTION;
            outputFunction = LONG_DECIMAL_OUTPUT_FUNCTION;
        }
        outputFunction = outputFunction.bindTo(type);

        return new AggregationMetadata(
                inputFunction,
                Optional.empty(),
                Optional.of(COMBINE_FUNCTION),
                outputFunction,
                ImmutableList.of(new AccumulatorStateDescriptor<>(
                        stateInterface,
                        stateSerializer,
                        new LongDecimalWithOverflowAndLongStateFactory())));
    }

    public static void inputShortDecimal(LongDecimalWithOverflowAndLongState state, Block block, int position)
    {
        state.addLong(1); // row counter

        state.setNotNull();

        long[] decimal = state.getDecimalArray();
        int offset = state.getDecimalArrayOffset();

        long rightLow = block.getLong(position, 0);
        long rightHigh = 0;
        if (rightLow < 0) {
            rightLow = -rightLow;
            rightHigh = SIGN_LONG_MASK;
        }

        long overflow = addWithOverflow(
                decimal[offset],
                decimal[offset + 1],
                rightLow,
                rightHigh,
                decimal,
                offset);
        state.addOverflow(overflow);
    }

    public static void inputLongDecimal(LongDecimalWithOverflowAndLongState state, Block block, int position)
    {
        state.addLong(1); // row counter

        state.setNotNull();

        long[] decimal = state.getDecimalArray();
        int offset = state.getDecimalArrayOffset();

        long overflow = addWithOverflow(
                decimal[offset],
                decimal[offset + 1],
                block.getLong(position, 0),
                block.getLong(position, SIZE_OF_LONG),
                decimal,
                offset);
        state.addOverflow(overflow);
    }

    public static void combine(LongDecimalWithOverflowAndLongState state, LongDecimalWithOverflowAndLongState otherState)
    {
        state.addLong(otherState.getLong()); // row counter

        long overflow = otherState.getOverflow();

        long[] decimal = state.getDecimalArray();
        int offset = state.getDecimalArrayOffset();

        long[] otherDecimal = otherState.getDecimalArray();
        int otherOffset = otherState.getDecimalArrayOffset();

        if (state.isNotNull()) {
            overflow += addWithOverflow(
                    decimal[offset],
                    decimal[offset + 1],
                    otherDecimal[otherOffset],
                    otherDecimal[otherOffset + 1],
                    decimal,
                    offset);
        }
        else {
            state.setNotNull();
            decimal[offset] = otherDecimal[otherOffset];
            decimal[offset + 1] = otherDecimal[otherOffset + 1];
        }

        state.addOverflow(overflow);
    }

    public static void outputShortDecimal(DecimalType type, LongDecimalWithOverflowAndLongState state, BlockBuilder out)
    {
        if (state.getLong() == 0) {
            out.appendNull();
        }
        else {
            writeShortDecimal(out, unscaledDecimalToUnscaledLong(average(state, type)));
        }
    }

    public static void outputLongDecimal(DecimalType type, LongDecimalWithOverflowAndLongState state, BlockBuilder out)
    {
        if (state.getLong() == 0) {
            out.appendNull();
        }
        else {
            type.writeSlice(out, average(state, type));
        }
    }

    @VisibleForTesting
    public static Slice average(LongDecimalWithOverflowAndLongState state, DecimalType type)
    {
        long[] decimal = state.getDecimalArray();
        int offset = state.getDecimalArrayOffset();

        long overflow = state.getOverflow();
        if (overflow != 0) {
            BigDecimal sum = new BigDecimal(unscaledDecimalToBigInteger(decimal[offset], decimal[offset + 1]), type.getScale());
            BigDecimal count = BigDecimal.valueOf(state.getLong());
            sum = sum.add(new BigDecimal(OVERFLOW_MULTIPLIER.multiply(BigInteger.valueOf(overflow))));
            return Decimals.encodeScaledValue(sum.divide(count, type.getScale(), ROUND_HALF_UP), type.getScale());
        }
        return divideRoundUp(decimal[offset], decimal[offset + 1], 0, state.getLong(), 0);
    }
}
