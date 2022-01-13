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
package io.trino.plugin.phoenix;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.trino.Session;
import io.trino.plugin.jdbc.UnsupportedTypeHandling;
import io.trino.spi.type.ArrayType;
import io.trino.spi.type.TimeZoneKey;
import io.trino.testing.AbstractTestQueryFramework;
import io.trino.testing.QueryRunner;
import io.trino.testing.datatype.CreateAndInsertDataSetup;
import io.trino.testing.datatype.CreateAsSelectDataSetup;
import io.trino.testing.datatype.DataSetup;
import io.trino.testing.datatype.DataType;
import io.trino.testing.datatype.DataTypeTest;
import io.trino.testing.datatype.SqlDataTypeTest;
import io.trino.testing.sql.TestTable;
import io.trino.testing.sql.TrinoSqlExecutor;
import io.trino.tpch.TpchTable;
import org.testng.annotations.AfterClass;
import org.testng.annotations.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Function;

import static com.google.common.base.Preconditions.checkState;
import static com.google.common.base.Verify.verify;
import static io.trino.plugin.jdbc.DecimalConfig.DecimalMapping.ALLOW_OVERFLOW;
import static io.trino.plugin.jdbc.DecimalConfig.DecimalMapping.STRICT;
import static io.trino.plugin.jdbc.DecimalSessionSessionProperties.DECIMAL_DEFAULT_SCALE;
import static io.trino.plugin.jdbc.DecimalSessionSessionProperties.DECIMAL_MAPPING;
import static io.trino.plugin.jdbc.DecimalSessionSessionProperties.DECIMAL_ROUNDING_MODE;
import static io.trino.plugin.jdbc.TypeHandlingJdbcSessionProperties.UNSUPPORTED_TYPE_HANDLING;
import static io.trino.plugin.jdbc.UnsupportedTypeHandling.CONVERT_TO_VARCHAR;
import static io.trino.plugin.phoenix.PhoenixQueryRunner.createPhoenixQueryRunner;
import static io.trino.spi.type.DateType.DATE;
import static io.trino.spi.type.DecimalType.createDecimalType;
import static io.trino.spi.type.IntegerType.INTEGER;
import static io.trino.spi.type.TimeZoneKey.UTC_KEY;
import static io.trino.spi.type.VarbinaryType.VARBINARY;
import static io.trino.testing.datatype.DataType.bigintDataType;
import static io.trino.testing.datatype.DataType.booleanDataType;
import static io.trino.testing.datatype.DataType.dataType;
import static io.trino.testing.datatype.DataType.dateDataType;
import static io.trino.testing.datatype.DataType.doubleDataType;
import static io.trino.testing.datatype.DataType.integerDataType;
import static io.trino.testing.datatype.DataType.realDataType;
import static io.trino.testing.datatype.DataType.smallintDataType;
import static io.trino.testing.datatype.DataType.tinyintDataType;
import static io.trino.testing.datatype.DataType.varcharDataType;
import static java.lang.String.format;
import static java.math.RoundingMode.HALF_UP;
import static java.math.RoundingMode.UNNECESSARY;
import static java.util.Arrays.asList;
import static java.util.Collections.singletonList;
import static java.util.stream.Collectors.toList;

public class TestPhoenixTypeMapping
        extends AbstractTestQueryFramework
{
    private TestingPhoenixServer phoenixServer;

    @Override
    protected QueryRunner createQueryRunner()
            throws Exception
    {
        phoenixServer = TestingPhoenixServer.getInstance();
        return createPhoenixQueryRunner(phoenixServer, ImmutableMap.of(), TpchTable.getTables());
    }

    @AfterClass(alwaysRun = true)
    public void destroy()
    {
        TestingPhoenixServer.shutDown();
    }

    @Test
    public void testBasicTypes()
    {
        DataTypeTest.create()
                .addRoundTrip(booleanDataType(), true)
                .addRoundTrip(booleanDataType(), false)
                .addRoundTrip(bigintDataType(), 123_456_789_012L)
                .addRoundTrip(integerDataType(), 1_234_567_890)
                .addRoundTrip(smallintDataType(), (short) 32_456)
                .addRoundTrip(tinyintDataType(), (byte) 5)
                .addRoundTrip(doubleDataType(), 123.45d)
                .addRoundTrip(realDataType(), 123.45f)
                .execute(getQueryRunner(), trinoCreateAsSelect("test_basic_types"));
    }

    @Test
    public void testVarchar()
    {
        DataTypeTest varcharTypeTest = stringDataTypeTest(DataType::varcharDataType)
                .addRoundTrip(varcharDataType(10485760), "text_f")
                .addRoundTrip(varcharDataType(), "unbounded");

        varcharTypeTest
                .execute(getQueryRunner(), trinoCreateAsSelect("test_varchar"));

        varcharTypeTest
                .addRoundTrip(primaryKey(), 1)
                .execute(getQueryRunner(), phoenixCreateAndInsert("tpch.test_varchar"));
    }

    private DataTypeTest stringDataTypeTest(Function<Integer, DataType<String>> dataTypeFactory)
    {
        return DataTypeTest.create()
                .addRoundTrip(dataTypeFactory.apply(10), "text_a")
                .addRoundTrip(dataTypeFactory.apply(255), "text_b")
                .addRoundTrip(dataTypeFactory.apply(65535), "text_d");
    }

    @Test
    public void testChar()
    {
        stringDataTypeTest(DataType::charDataType)
                .execute(getQueryRunner(), trinoCreateAsSelect("test_char"));

        stringDataTypeTest(DataType::charDataType)
                .addRoundTrip(primaryKey(), 1)
                .execute(getQueryRunner(), phoenixCreateAndInsert("tpch.test_char"));
    }

    @Test
    public void testVarbinary()
    {
        SqlDataTypeTest.create()
                .addRoundTrip("varbinary", "NULL", VARBINARY, "CAST(NULL AS varbinary)")
                .addRoundTrip("varbinary", "X''", VARBINARY, "CAST(NULL AS varbinary)") // empty stored as NULL
                .addRoundTrip("varbinary", "X'68656C6C6F'", VARBINARY, "to_utf8('hello')")
                .addRoundTrip("varbinary", "X'5069C4996B6E6120C582C4856B61207720E69DB1E4BAACE983BD'", VARBINARY, "to_utf8('Piękna łąka w 東京都')")
                .addRoundTrip("varbinary", "X'4261672066756C6C206F6620F09F92B0'", VARBINARY, "to_utf8('Bag full of 💰')")
                .addRoundTrip("varbinary", "X'0001020304050607080DF9367AA7000000'", VARBINARY, "X'0001020304050607080DF9367AA7000000'") // non-text
                .addRoundTrip("varbinary", "X'000000000000'", VARBINARY, "X'000000000000'")
                .execute(getQueryRunner(), trinoCreateAsSelect("test_varbinary"));

        SqlDataTypeTest.create()
                .addRoundTrip("integer primary key", "1", INTEGER, "1")
                .addRoundTrip("varbinary", "NULL", VARBINARY, "CAST(NULL AS varbinary)")
                .addRoundTrip("varbinary", "DECODE('', 'HEX')", VARBINARY, "CAST(NULL AS varbinary)") // empty stored as NULL
                .addRoundTrip("varbinary", "DECODE('68656C6C6F', 'HEX')", VARBINARY, "to_utf8('hello')")
                .addRoundTrip("varbinary", "DECODE('5069C4996B6E6120C582C4856B61207720E69DB1E4BAACE983BD', 'HEX')", VARBINARY, "to_utf8('Piękna łąka w 東京都')")
                .addRoundTrip("varbinary", "DECODE('4261672066756C6C206F6620F09F92B0', 'HEX')", VARBINARY, "to_utf8('Bag full of 💰')")
                .addRoundTrip("varbinary", "DECODE('0001020304050607080DF9367AA7000000', 'HEX')", VARBINARY, "X'0001020304050607080DF9367AA7000000'") // non-text
                .addRoundTrip("varbinary", "DECODE('000000000000', 'HEX')", VARBINARY, "X'000000000000'")
                .execute(getQueryRunner(), phoenixCreateAndInsert("tpch.test_varbinary"));
    }

    @Test
    public void testDecimal()
    {
        decimalTests(DataType::decimalDataType)
                .execute(getQueryRunner(), trinoCreateAsSelect("test_decimal"));

        decimalTests(TestPhoenixTypeMapping::phoenixDecimalDataType)
                .addRoundTrip(primaryKey(), 1)
                .execute(getQueryRunner(), phoenixCreateAndInsert("tpch.test_decimal"));
    }

    private DataTypeTest decimalTests(BiFunction<Integer, Integer, DataType<BigDecimal>> dataTypeFactory)
    {
        return DataTypeTest.create()
                .addRoundTrip(dataTypeFactory.apply(3, 0), new BigDecimal("193"))
                .addRoundTrip(dataTypeFactory.apply(3, 0), new BigDecimal("19"))
                .addRoundTrip(dataTypeFactory.apply(3, 0), new BigDecimal("-193"))
                .addRoundTrip(dataTypeFactory.apply(3, 1), new BigDecimal("10.0"))
                .addRoundTrip(dataTypeFactory.apply(3, 1), new BigDecimal("10.1"))
                .addRoundTrip(dataTypeFactory.apply(3, 1), new BigDecimal("-10.1"))
                .addRoundTrip(dataTypeFactory.apply(3, 2), new BigDecimal("3.14"))
                .addRoundTrip(dataTypeFactory.apply(4, 2), new BigDecimal("2"))
                .addRoundTrip(dataTypeFactory.apply(4, 2), new BigDecimal("2.3"))
                .addRoundTrip(dataTypeFactory.apply(24, 2), new BigDecimal("2"))
                .addRoundTrip(dataTypeFactory.apply(24, 2), new BigDecimal("2.3"))
                .addRoundTrip(dataTypeFactory.apply(24, 2), new BigDecimal("123456789.3"))
                .addRoundTrip(dataTypeFactory.apply(24, 4), new BigDecimal("12345678901234567890.31"))
                .addRoundTrip(dataTypeFactory.apply(24, 23), new BigDecimal("3.12345678901234567890123"))
                .addRoundTrip(dataTypeFactory.apply(30, 5), new BigDecimal("3141592653589793238462643.38327"))
                .addRoundTrip(dataTypeFactory.apply(30, 5), new BigDecimal("-3141592653589793238462643.38327"))
                .addRoundTrip(dataTypeFactory.apply(38, 0), new BigDecimal("27182818284590452353602874713526624977"))
                .addRoundTrip(dataTypeFactory.apply(38, 0), new BigDecimal("-27182818284590452353602874713526624977"));
    }

    private static DataType<BigDecimal> phoenixDecimalDataType(int precision, int scale)
    {
        String databaseType = format("decimal(%s, %s)", precision, scale);
        return dataType(
                databaseType,
                createDecimalType(precision, scale),
                bigDecimal -> format("CAST(%s AS %s)", bigDecimal, databaseType),
                bigDecimal -> bigDecimal.setScale(scale, UNNECESSARY));
    }

    @Test
    public void testDecimalUnspecifiedPrecision()
    {
        PhoenixSqlExecutor phoenixSqlExecutor = new PhoenixSqlExecutor(phoenixServer.getJdbcUrl());
        try (TestTable testTable = new TestTable(
                phoenixSqlExecutor,
                "tpch.test_var_decimal",
                "(pk bigint primary key, d_col decimal)",
                asList("1, 1.12", "2, 123456.789", "3, -1.12", "4, -123456.789"))) {
            assertQueryFails(
                    sessionWithDecimalMappingAllowOverflow(UNNECESSARY, 0),
                    "SELECT d_col FROM " + testTable.getName(),
                    "Rounding necessary");
            assertQuery(
                    sessionWithDecimalMappingAllowOverflow(HALF_UP, 0),
                    "SELECT d_col FROM " + testTable.getName(),
                    "VALUES (1), (123457), (-1), (-123457)");
            assertQueryFails(
                    sessionWithDecimalMappingAllowOverflow(UNNECESSARY, 1),
                    "SELECT d_col FROM " + testTable.getName(),
                    "Rounding necessary");
            assertQuery(
                    sessionWithDecimalMappingAllowOverflow(HALF_UP, 1),
                    "SELECT d_col FROM " + testTable.getName(),
                    "VALUES (1.1), (123456.8), (-1.1), (-123456.8)");
            assertQueryFails(
                    sessionWithDecimalMappingAllowOverflow(UNNECESSARY, 2),
                    "SELECT d_col FROM " + testTable.getName(),
                    "Rounding necessary");
            assertQuery(
                    sessionWithDecimalMappingAllowOverflow(HALF_UP, 2),
                    "SELECT d_col FROM " + testTable.getName(),
                    "VALUES (1.12), (123456.79), (-1.12), (-123456.79)");
            assertQuery(
                    sessionWithDecimalMappingAllowOverflow(UNNECESSARY, 3),
                    "SELECT d_col FROM " + testTable.getName(),
                    "VALUES (1.12), (123456.789), (-1.12), (-123456.789)");
            assertQueryFails(
                    sessionWithDecimalMappingStrict(CONVERT_TO_VARCHAR),
                    "SELECT d_col FROM " + testTable.getName(),
                    "Rounding necessary");
        }
    }

    @Test
    public void testDate()
    {
        // Note: there is identical test for MySQL

        ZoneId jvmZone = ZoneId.systemDefault();
        checkState(jvmZone.getId().equals("America/Bahia_Banderas"), "This test assumes certain JVM time zone");
        LocalDate dateOfLocalTimeChangeForwardAtMidnightInJvmZone = LocalDate.of(1970, 1, 1);
        checkIsGap(jvmZone, dateOfLocalTimeChangeForwardAtMidnightInJvmZone.atStartOfDay());

        ZoneId someZone = ZoneId.of("Europe/Vilnius");
        LocalDate dateOfLocalTimeChangeForwardAtMidnightInSomeZone = LocalDate.of(1983, 4, 1);
        checkIsGap(someZone, dateOfLocalTimeChangeForwardAtMidnightInSomeZone.atStartOfDay());
        LocalDate dateOfLocalTimeChangeBackwardAtMidnightInSomeZone = LocalDate.of(1983, 10, 1);
        checkIsDoubled(someZone, dateOfLocalTimeChangeBackwardAtMidnightInSomeZone.atStartOfDay().minusMinutes(1));

        DataTypeTest trinoTestCases = dateTests(
                dateOfLocalTimeChangeForwardAtMidnightInJvmZone,
                dateOfLocalTimeChangeForwardAtMidnightInSomeZone,
                dateOfLocalTimeChangeBackwardAtMidnightInSomeZone,
                dateDataType());

        DataTypeTest phoenixTestCases = dateTests(
                dateOfLocalTimeChangeForwardAtMidnightInJvmZone,
                dateOfLocalTimeChangeForwardAtMidnightInSomeZone,
                dateOfLocalTimeChangeBackwardAtMidnightInSomeZone,
                phoenixDateDataType())
                .addRoundTrip(primaryKey(), 1);

        for (String timeZoneId : ImmutableList.of(UTC_KEY.getId(), jvmZone.getId(), someZone.getId())) {
            Session session = Session.builder(getSession())
                    .setTimeZoneKey(TimeZoneKey.getTimeZoneKey(timeZoneId))
                    .build();
            trinoTestCases.execute(getQueryRunner(), session, trinoCreateAsSelect(session, "test_date"));
            trinoTestCases.execute(getQueryRunner(), session, trinoCreateAsSelect(getSession(), "test_date"));
            trinoTestCases.execute(getQueryRunner(), session, trinoCreateAndInsert(session, "test_date"));
            phoenixTestCases.execute(getQueryRunner(), session, phoenixCreateAndInsert("tpch.test_date"));
        }
    }

    @Test
    public void testArray()
    {
        // basic types
        DataTypeTest.create()
                .addRoundTrip(arrayDataType(booleanDataType()), asList(true, false))
                .addRoundTrip(arrayDataType(bigintDataType()), asList(123_456_789_012L))
                .addRoundTrip(arrayDataType(integerDataType()), asList(1, 2, 1_234_567_890))
                .addRoundTrip(arrayDataType(smallintDataType()), asList((short) 32_456))
                .addRoundTrip(arrayDataType(doubleDataType()), asList(123.45d))
                .addRoundTrip(arrayDataType(realDataType()), asList(123.45f))
                .execute(getQueryRunner(), trinoCreateAsSelect("test_array_basic"));

        arrayDateTest(TestPhoenixTypeMapping::arrayDataType, dateDataType())
                .execute(getQueryRunner(), trinoCreateAsSelect("test_array_date"));
        arrayDateTest(TestPhoenixTypeMapping::phoenixArrayDataType, phoenixDateDataType())
                .addRoundTrip(primaryKey(), 1)
                .execute(getQueryRunner(), phoenixCreateAndInsert("tpch.test_array_date"));

        arrayDecimalTest(TestPhoenixTypeMapping::arrayDataType, DataType::decimalDataType)
                .execute(getQueryRunner(), trinoCreateAsSelect("test_array_decimal"));
        arrayDecimalTest(TestPhoenixTypeMapping::phoenixArrayDataType, TestPhoenixTypeMapping::phoenixDecimalDataType)
                .addRoundTrip(primaryKey(), 1)
                .execute(getQueryRunner(), phoenixCreateAndInsert("tpch.test_array_decimal"));

        arrayStringDataTypeTest(TestPhoenixTypeMapping::arrayDataType, DataType::charDataType)
                .execute(getQueryRunner(), trinoCreateAsSelect("test_array_char"));
        arrayStringDataTypeTest(TestPhoenixTypeMapping::phoenixArrayDataType, DataType::charDataType)
                .addRoundTrip(primaryKey(), 1)
                .execute(getQueryRunner(), phoenixCreateAndInsert("tpch.test_array_char"));

        arrayStringDataTypeTest(TestPhoenixTypeMapping::arrayDataType, DataType::varcharDataType)
                .addRoundTrip(arrayDataType(varcharDataType(10485760)), asList("text_f"))
                .addRoundTrip(arrayDataType(varcharDataType()), asList("unbounded"))
                .execute(getQueryRunner(), trinoCreateAsSelect("test_array_varchar"));
        arrayStringDataTypeTest(TestPhoenixTypeMapping::phoenixArrayDataType, DataType::varcharDataType)
                .addRoundTrip(phoenixArrayDataType(varcharDataType(10485760)), asList("text_f"))
                .addRoundTrip(phoenixArrayDataType(varcharDataType()), asList("unbounded"))
                .addRoundTrip(primaryKey(), 1)
                .execute(getQueryRunner(), phoenixCreateAndInsert("tpch.test_array_varchar"));
    }

    @Test
    public void testArrayNulls()
    {
        DataTypeTest.create()
                .addRoundTrip(arrayDataType(booleanDataType()), null)
                .addRoundTrip(arrayDataType(varcharDataType()), singletonList(null))
                .addRoundTrip(arrayDataType(varcharDataType()), asList("foo", null, "bar", null))
                .execute(getQueryRunner(), trinoCreateAsSelect("test_array_nulls"));
    }

    private DataTypeTest arrayDecimalTest(Function<DataType<BigDecimal>, DataType<List<BigDecimal>>> arrayTypeFactory, BiFunction<Integer, Integer, DataType<BigDecimal>> decimalTypeFactory)
    {
        return DataTypeTest.create()
                .addRoundTrip(arrayTypeFactory.apply(decimalTypeFactory.apply(3, 0)), asList(new BigDecimal("193"), new BigDecimal("19"), new BigDecimal("-193")))
                .addRoundTrip(arrayTypeFactory.apply(decimalTypeFactory.apply(3, 1)), asList(new BigDecimal("10.0"), new BigDecimal("10.1"), new BigDecimal("-10.1")))
                .addRoundTrip(arrayTypeFactory.apply(decimalTypeFactory.apply(4, 2)), asList(new BigDecimal("2"), new BigDecimal("2.3")))
                .addRoundTrip(arrayTypeFactory.apply(decimalTypeFactory.apply(24, 2)), asList(new BigDecimal("2"), new BigDecimal("2.3"), new BigDecimal("123456789.3")))
                .addRoundTrip(arrayTypeFactory.apply(decimalTypeFactory.apply(24, 4)), asList(new BigDecimal("12345678901234567890.31")))
                .addRoundTrip(arrayTypeFactory.apply(decimalTypeFactory.apply(30, 5)), asList(new BigDecimal("3141592653589793238462643.38327"), new BigDecimal("-3141592653589793238462643.38327")))
                .addRoundTrip(arrayTypeFactory.apply(decimalTypeFactory.apply(38, 0)), asList(
                        new BigDecimal("27182818284590452353602874713526624977"),
                        new BigDecimal("-27182818284590452353602874713526624977")));
    }

    private DataTypeTest arrayStringDataTypeTest(Function<DataType<String>, DataType<List<String>>> arrayTypeFactory, Function<Integer, DataType<String>> dataTypeFactory)
    {
        return DataTypeTest.create()
                .addRoundTrip(arrayTypeFactory.apply(dataTypeFactory.apply(10)), asList("text_a"))
                .addRoundTrip(arrayTypeFactory.apply(dataTypeFactory.apply(255)), asList("text_b"))
                .addRoundTrip(arrayTypeFactory.apply(dataTypeFactory.apply(65535)), asList("text_d"));
    }

    private DataTypeTest arrayDateTest(Function<DataType<LocalDate>, DataType<List<LocalDate>>> arrayTypeFactory, DataType<LocalDate> dateDataType)
    {
        ZoneId jvmZone = ZoneId.systemDefault();
        checkState(jvmZone.getId().equals("America/Bahia_Banderas"), "This test assumes certain JVM time zone");
        LocalDate dateOfLocalTimeChangeForwardAtMidnightInJvmZone = LocalDate.of(1970, 1, 1);
        checkIsGap(jvmZone, dateOfLocalTimeChangeForwardAtMidnightInJvmZone.atStartOfDay());

        ZoneId someZone = ZoneId.of("Europe/Vilnius");
        LocalDate dateOfLocalTimeChangeForwardAtMidnightInSomeZone = LocalDate.of(1983, 4, 1);
        checkIsGap(someZone, dateOfLocalTimeChangeForwardAtMidnightInSomeZone.atStartOfDay());
        LocalDate dateOfLocalTimeChangeBackwardAtMidnightInSomeZone = LocalDate.of(1983, 10, 1);
        checkIsDoubled(someZone, dateOfLocalTimeChangeBackwardAtMidnightInSomeZone.atStartOfDay().minusMinutes(1));

        return DataTypeTest.create()
                .addRoundTrip(arrayTypeFactory.apply(dateDataType), asList(LocalDate.of(1952, 4, 3))) // before epoch
                .addRoundTrip(arrayTypeFactory.apply(dateDataType), asList(LocalDate.of(1970, 1, 1)))
                .addRoundTrip(arrayTypeFactory.apply(dateDataType), asList(LocalDate.of(1970, 2, 3)))
                .addRoundTrip(arrayTypeFactory.apply(dateDataType), asList(LocalDate.of(2017, 7, 1))) // summer on northern hemisphere (possible DST)
                .addRoundTrip(arrayTypeFactory.apply(dateDataType), asList(LocalDate.of(2017, 1, 1))) // winter on northern hemisphere (possible DST on southern hemisphere)
                .addRoundTrip(arrayTypeFactory.apply(dateDataType), asList(dateOfLocalTimeChangeForwardAtMidnightInJvmZone))
                .addRoundTrip(arrayTypeFactory.apply(dateDataType), asList(dateOfLocalTimeChangeForwardAtMidnightInSomeZone))
                .addRoundTrip(arrayTypeFactory.apply(dateDataType), asList(dateOfLocalTimeChangeBackwardAtMidnightInSomeZone));
    }

    private DataTypeTest dateTests(
            LocalDate dateOfLocalTimeChangeForwardAtMidnightInJvmZone,
            LocalDate dateOfLocalTimeChangeForwardAtMidnightInSomeZone,
            LocalDate dateOfLocalTimeChangeBackwardAtMidnightInSomeZone,
            DataType<LocalDate> dateDataType)
    {
        return DataTypeTest.create()
                .addRoundTrip(dateDataType, LocalDate.of(1952, 4, 3)) // before epoch
                .addRoundTrip(dateDataType, LocalDate.of(1970, 1, 1))
                .addRoundTrip(dateDataType, LocalDate.of(1970, 2, 3))
                .addRoundTrip(dateDataType, LocalDate.of(2017, 7, 1)) // summer on northern hemisphere (possible DST)
                .addRoundTrip(dateDataType, LocalDate.of(2017, 1, 1)) // winter on northern hemisphere (possible DST on southern hemisphere)
                .addRoundTrip(dateDataType, dateOfLocalTimeChangeForwardAtMidnightInJvmZone)
                .addRoundTrip(dateDataType, dateOfLocalTimeChangeForwardAtMidnightInSomeZone)
                .addRoundTrip(dateDataType, dateOfLocalTimeChangeBackwardAtMidnightInSomeZone);
    }

    private static <E> DataType<List<E>> arrayDataType(DataType<E> elementType)
    {
        return arrayDataType(elementType, format("ARRAY(%s)", elementType.getInsertType()));
    }

    private static <E> DataType<List<E>> phoenixArrayDataType(DataType<E> elementType)
    {
        return arrayDataType(elementType, elementType.getInsertType() + " ARRAY");
    }

    private static <E> DataType<List<E>> arrayDataType(DataType<E> elementType, String insertType)
    {
        return dataType(
                insertType,
                new ArrayType(elementType.getTrinoResultType()),
                valuesList -> "ARRAY" + valuesList.stream().map(elementType::toLiteral).collect(toList()),
                valuesList -> valuesList == null ? null : valuesList.stream().map(elementType::toTrinoQueryResult).collect(toList()));
    }

    public static DataType<LocalDate> phoenixDateDataType()
    {
        return dataType(
                "date",
                DATE,
                value -> format("TO_DATE('%s', 'yyyy-MM-dd', 'local')", DateTimeFormatter.ofPattern("uuuu-MM-dd").format(value)));
    }

    private static void checkIsGap(ZoneId zone, LocalDateTime dateTime)
    {
        verify(isGap(zone, dateTime), "Expected %s to be a gap in %s", dateTime, zone);
    }

    private static boolean isGap(ZoneId zone, LocalDateTime dateTime)
    {
        return zone.getRules().getValidOffsets(dateTime).isEmpty();
    }

    private static void checkIsDoubled(ZoneId zone, LocalDateTime dateTime)
    {
        verify(zone.getRules().getValidOffsets(dateTime).size() == 2, "Expected %s to be doubled in %s", dateTime, zone);
    }

    private DataType<Integer> primaryKey()
    {
        return dataType("integer primary key", INTEGER, Object::toString);
    }

    private DataSetup trinoCreateAsSelect(String tableNamePrefix)
    {
        return trinoCreateAsSelect(getSession(), tableNamePrefix);
    }

    private DataSetup trinoCreateAsSelect(Session session, String tableNamePrefix)
    {
        return new CreateAsSelectDataSetup(new TrinoSqlExecutor(getQueryRunner(), session), tableNamePrefix);
    }

    private DataSetup trinoCreateAndInsert(Session session, String tableNamePrefix)
    {
        return new CreateAndInsertDataSetup(new TrinoSqlExecutor(getQueryRunner(), session), tableNamePrefix);
    }

    private DataSetup phoenixCreateAndInsert(String tableNamePrefix)
    {
        return new CreateAndInsertDataSetup(new PhoenixSqlExecutor(phoenixServer.getJdbcUrl()), tableNamePrefix);
    }

    private Session sessionWithDecimalMappingAllowOverflow(RoundingMode roundingMode, int scale)
    {
        return Session.builder(getSession())
                .setCatalogSessionProperty("phoenix", DECIMAL_MAPPING, ALLOW_OVERFLOW.name())
                .setCatalogSessionProperty("phoenix", DECIMAL_ROUNDING_MODE, roundingMode.name())
                .setCatalogSessionProperty("phoenix", DECIMAL_DEFAULT_SCALE, Integer.valueOf(scale).toString())
                .build();
    }

    private Session sessionWithDecimalMappingStrict(UnsupportedTypeHandling unsupportedTypeHandling)
    {
        return Session.builder(getSession())
                .setCatalogSessionProperty("phoenix", DECIMAL_MAPPING, STRICT.name())
                .setCatalogSessionProperty("phoenix", UNSUPPORTED_TYPE_HANDLING, unsupportedTypeHandling.name())
                .build();
    }
}
