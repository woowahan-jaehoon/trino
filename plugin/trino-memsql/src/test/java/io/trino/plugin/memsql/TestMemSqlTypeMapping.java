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
package io.trino.plugin.memsql;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.trino.Session;
import io.trino.plugin.jdbc.UnsupportedTypeHandling;
import io.trino.spi.type.TimeZoneKey;
import io.trino.spi.type.VarcharType;
import io.trino.testing.AbstractTestQueryFramework;
import io.trino.testing.QueryRunner;
import io.trino.testing.TestingSession;
import io.trino.testing.datatype.CreateAndInsertDataSetup;
import io.trino.testing.datatype.CreateAsSelectDataSetup;
import io.trino.testing.datatype.DataSetup;
import io.trino.testing.datatype.DataType;
import io.trino.testing.datatype.DataTypeTest;
import io.trino.testing.datatype.SqlDataTypeTest;
import io.trino.testing.sql.SqlExecutor;
import io.trino.testing.sql.TestTable;
import io.trino.testing.sql.TrinoSqlExecutor;
import org.testng.annotations.AfterClass;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Objects;
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
import static io.trino.plugin.memsql.MemSqlClient.MEMSQL_VARCHAR_MAX_LENGTH;
import static io.trino.plugin.memsql.MemSqlQueryRunner.createMemSqlQueryRunner;
import static io.trino.spi.type.BigintType.BIGINT;
import static io.trino.spi.type.BooleanType.BOOLEAN;
import static io.trino.spi.type.DateType.DATE;
import static io.trino.spi.type.DecimalType.createDecimalType;
import static io.trino.spi.type.DoubleType.DOUBLE;
import static io.trino.spi.type.IntegerType.INTEGER;
import static io.trino.spi.type.RealType.REAL;
import static io.trino.spi.type.SmallintType.SMALLINT;
import static io.trino.spi.type.TimeZoneKey.UTC_KEY;
import static io.trino.spi.type.TimeZoneKey.getTimeZoneKey;
import static io.trino.spi.type.TimestampType.createTimestampType;
import static io.trino.spi.type.TinyintType.TINYINT;
import static io.trino.spi.type.VarbinaryType.VARBINARY;
import static io.trino.spi.type.VarcharType.createUnboundedVarcharType;
import static io.trino.spi.type.VarcharType.createVarcharType;
import static io.trino.testing.datatype.DataType.bigintDataType;
import static io.trino.testing.datatype.DataType.charDataType;
import static io.trino.testing.datatype.DataType.dataType;
import static io.trino.testing.datatype.DataType.dateDataType;
import static io.trino.testing.datatype.DataType.decimalDataType;
import static io.trino.testing.datatype.DataType.doubleDataType;
import static io.trino.testing.datatype.DataType.formatStringLiteral;
import static io.trino.testing.datatype.DataType.integerDataType;
import static io.trino.testing.datatype.DataType.realDataType;
import static io.trino.testing.datatype.DataType.smallintDataType;
import static io.trino.testing.datatype.DataType.stringDataType;
import static io.trino.testing.datatype.DataType.tinyintDataType;
import static io.trino.testing.datatype.DataType.varcharDataType;
import static io.trino.type.JsonType.JSON;
import static java.lang.String.format;
import static java.math.RoundingMode.HALF_UP;
import static java.math.RoundingMode.UNNECESSARY;
import static java.time.ZoneOffset.UTC;
import static java.util.Arrays.asList;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class TestMemSqlTypeMapping
        extends AbstractTestQueryFramework
{
    private static final String CHARACTER_SET_UTF8 = "CHARACTER SET utf8";

    protected TestingMemSqlServer memSqlServer;

    @Override
    protected QueryRunner createQueryRunner()
            throws Exception
    {
        memSqlServer = new TestingMemSqlServer();
        return createMemSqlQueryRunner(memSqlServer, ImmutableMap.of(), ImmutableMap.of(), ImmutableList.of());
    }

    @AfterClass(alwaysRun = true)
    public final void destroy()
    {
        memSqlServer.close();
    }

    @Test
    public void testBasicTypes()
    {
        DataTypeTest.create()
                .addRoundTrip(bigintDataType(), 123_456_789_012L)
                .addRoundTrip(integerDataType(), 1_234_567_890)
                .addRoundTrip(smallintDataType(), (short) 32_456)
                .addRoundTrip(tinyintDataType(), (byte) 125)
                .addRoundTrip(doubleDataType(), 123.45d)
                .addRoundTrip(realDataType(), 123.45f)
                .execute(getQueryRunner(), trinoCreateAsSelect("test_basic_types"));
    }

    @Test
    public void testBit()
    {
        SqlDataTypeTest.create()
                .addRoundTrip("bit", "b'1'", BOOLEAN, "true")
                .addRoundTrip("bit", "b'0'", BOOLEAN, "false")
                .addRoundTrip("bit", "NULL", BOOLEAN, "CAST(NULL AS BOOLEAN)")
                .execute(getQueryRunner(), memSqlCreateAndInsert("tpch.test_bit"));
    }

    @Test
    public void testBoolean()
    {
        SqlDataTypeTest.create()
                .addRoundTrip("boolean", "true", TINYINT, "TINYINT '1'")
                .addRoundTrip("boolean", "false", TINYINT, "TINYINT '0'")
                .addRoundTrip("boolean", "NULL", TINYINT, "CAST(NULL AS TINYINT)")
                .execute(getQueryRunner(), memSqlCreateAndInsert("tpch.test_boolean"))
                .execute(getQueryRunner(), trinoCreateAsSelect("tpch.test_boolean"));
    }

    @Test
    public void testFloat()
    {
        // we are not testing Nan/-Infinity/+Infinity as those are not supported by MemSQL
        SqlDataTypeTest.create()
                .addRoundTrip("real", "3.14", REAL, "REAL '3.14'")
                .addRoundTrip("real", "10.3e0", REAL, "REAL '10.3e0'")
                .addRoundTrip("real", "NULL", REAL, "CAST(NULL AS REAL)")
                // .addRoundTrip("real", "3.1415927", REAL, "REAL '3.1415927'") // Overeagerly rounded by MemSQL to 3.14159
                .execute(getQueryRunner(), trinoCreateAsSelect("trino_test_float"));

        SqlDataTypeTest.create()
                .addRoundTrip("float", "3.14", REAL, "REAL '3.14'")
                .addRoundTrip("float", "10.3e0", REAL, "REAL '10.3e0'")
                .addRoundTrip("float", "NULL", REAL, "CAST(NULL AS REAL)")
                // .addRoundTrip("float", "3.1415927", REAL, "REAL '3.1415927'") // Overeagerly rounded by MemSQL to 3.14159
                .execute(getQueryRunner(), memSqlCreateAndInsert("tpch.memsql_test_float"));
    }

    @Test
    public void testDouble()
    {
        doublePrecisionFloatingPointTests(doubleDataType())
                .execute(getQueryRunner(), trinoCreateAsSelect("trino_test_double"));
        doublePrecisionFloatingPointTests(memSqlDoubleDataType())
                .execute(getQueryRunner(), memSqlCreateAndInsert("tpch.memsql_test_double"));
    }

    private static DataTypeTest doublePrecisionFloatingPointTests(DataType<Double> doubleType)
    {
        // we are not testing Nan/-Infinity/+Infinity as those are not supported by MemSQL
        return DataTypeTest.create()
                .addRoundTrip(doubleType, 1.0e100d)
                .addRoundTrip(doubleType, 123.456E10)
                .addRoundTrip(doubleType, null);
    }

    @Test
    public void testUnsignedTypes()
    {
        DataType<Short> memSqlUnsignedTinyInt = DataType.dataType("TINYINT UNSIGNED", SMALLINT, Objects::toString);
        DataType<Integer> memSqlUnsignedSmallInt = DataType.dataType("SMALLINT UNSIGNED", INTEGER, Objects::toString);
        DataType<Long> memSqlUnsignedInt = DataType.dataType("INT UNSIGNED", BIGINT, Objects::toString);
        DataType<Long> memSqlUnsignedInteger = DataType.dataType("INTEGER UNSIGNED", BIGINT, Objects::toString);
        DataType<BigDecimal> memSqlUnsignedBigint = DataType.dataType("BIGINT UNSIGNED", createDecimalType(20), Objects::toString);

        DataTypeTest.create()
                .addRoundTrip(memSqlUnsignedTinyInt, (short) 255)
                .addRoundTrip(memSqlUnsignedSmallInt, 65_535)
                .addRoundTrip(memSqlUnsignedInt, 4_294_967_295L)
                .addRoundTrip(memSqlUnsignedInteger, 4_294_967_295L)
                .addRoundTrip(memSqlUnsignedBigint, new BigDecimal("18446744073709551615"))
                .execute(getQueryRunner(), memSqlCreateAndInsert("tpch.memsql_test_unsigned"));
    }

    @Test
    public void testMemsqlCreatedDecimal()
    {
        decimalTests()
                .execute(getQueryRunner(), memSqlCreateAndInsert("tpch.test_decimal"));
    }

    @Test
    public void testTrinoCreatedDecimal()
    {
        decimalTests()
                .execute(getQueryRunner(), trinoCreateAsSelect("test_decimal"));
    }

    private DataTypeTest decimalTests()
    {
        return DataTypeTest.create()
                .addRoundTrip(decimalDataType(3, 0), new BigDecimal("193"))
                .addRoundTrip(decimalDataType(3, 0), new BigDecimal("19"))
                .addRoundTrip(decimalDataType(3, 0), new BigDecimal("-193"))
                .addRoundTrip(decimalDataType(3, 1), new BigDecimal("10.0"))
                .addRoundTrip(decimalDataType(3, 1), new BigDecimal("10.1"))
                .addRoundTrip(decimalDataType(3, 1), new BigDecimal("-10.1"))
                .addRoundTrip(decimalDataType(4, 2), new BigDecimal("2"))
                .addRoundTrip(decimalDataType(4, 2), new BigDecimal("2.3"))
                .addRoundTrip(decimalDataType(24, 2), new BigDecimal("2"))
                .addRoundTrip(decimalDataType(24, 2), new BigDecimal("2.3"))
                .addRoundTrip(decimalDataType(24, 2), new BigDecimal("123456789.3"))
                .addRoundTrip(decimalDataType(24, 4), new BigDecimal("12345678901234567890.31"))
                .addRoundTrip(decimalDataType(30, 5), new BigDecimal("3141592653589793238462643.38327"))
                .addRoundTrip(decimalDataType(30, 5), new BigDecimal("-3141592653589793238462643.38327"))
                .addRoundTrip(decimalDataType(38, 0), new BigDecimal("27182818284590452353602874713526624977"))
                .addRoundTrip(decimalDataType(38, 0), new BigDecimal("-27182818284590452353602874713526624977"));
    }

    @Test
    public void testDecimalExceedingPrecisionMax()
    {
        testUnsupportedDataType("decimal(50,0)");
    }

    @Test
    public void testDecimalExceedingPrecisionMaxWithExceedingIntegerValues()
    {
        try (TestTable testTable = new TestTable(
                memSqlServer::execute,
                "tpch.test_exceeding_max_decimal",
                "(d_col decimal(65,25))",
                asList("1234567890123456789012345678901234567890.123456789", "-1234567890123456789012345678901234567890.123456789"))) {
            assertQuery(
                    sessionWithDecimalMappingAllowOverflow(UNNECESSARY, 0),
                    format("SELECT column_name, data_type FROM information_schema.columns WHERE table_schema = 'tpch' AND table_schema||'.'||table_name = '%s'", testTable.getName()),
                    "VALUES ('d_col', 'decimal(38,0)')");
            assertQueryFails(
                    sessionWithDecimalMappingAllowOverflow(UNNECESSARY, 0),
                    "SELECT d_col FROM " + testTable.getName(),
                    "Rounding necessary");
            assertQueryFails(
                    sessionWithDecimalMappingAllowOverflow(HALF_UP, 0),
                    "SELECT d_col FROM " + testTable.getName(),
                    "Decimal overflow");
            assertQuery(
                    sessionWithDecimalMappingStrict(CONVERT_TO_VARCHAR),
                    format("SELECT column_name, data_type FROM information_schema.columns WHERE table_schema = 'tpch' AND table_schema||'.'||table_name = '%s'", testTable.getName()),
                    "VALUES ('d_col', 'varchar')");
            assertQuery(
                    sessionWithDecimalMappingStrict(CONVERT_TO_VARCHAR),
                    "SELECT d_col FROM " + testTable.getName(),
                    "VALUES ('1234567890123456789012345678901234567890.1234567890000000000000000'), ('-1234567890123456789012345678901234567890.1234567890000000000000000')");
        }
    }

    @Test
    public void testDecimalExceedingPrecisionMaxWithNonExceedingIntegerValues()
    {
        try (TestTable testTable = new TestTable(
                memSqlServer::execute,
                "tpch.test_exceeding_max_decimal",
                "(d_col decimal(60,20))",
                asList("123456789012345678901234567890.123456789012345", "-123456789012345678901234567890.123456789012345"))) {
            assertQuery(
                    sessionWithDecimalMappingAllowOverflow(UNNECESSARY, 0),
                    format("SELECT column_name, data_type FROM information_schema.columns WHERE table_schema = 'tpch' AND table_schema||'.'||table_name = '%s'", testTable.getName()),
                    "VALUES ('d_col', 'decimal(38,0)')");
            assertQueryFails(
                    sessionWithDecimalMappingAllowOverflow(UNNECESSARY, 0),
                    "SELECT d_col FROM " + testTable.getName(),
                    "Rounding necessary");
            assertQuery(
                    sessionWithDecimalMappingAllowOverflow(HALF_UP, 0),
                    "SELECT d_col FROM " + testTable.getName(),
                    "VALUES (123456789012345678901234567890), (-123456789012345678901234567890)");
            assertQuery(
                    sessionWithDecimalMappingAllowOverflow(UNNECESSARY, 8),
                    format("SELECT column_name, data_type FROM information_schema.columns WHERE table_schema = 'tpch' AND table_schema||'.'||table_name = '%s'", testTable.getName()),
                    "VALUES ('d_col', 'decimal(38,8)')");
            assertQueryFails(
                    sessionWithDecimalMappingAllowOverflow(UNNECESSARY, 8),
                    "SELECT d_col FROM " + testTable.getName(),
                    "Rounding necessary");
            assertQuery(
                    sessionWithDecimalMappingAllowOverflow(HALF_UP, 8),
                    "SELECT d_col FROM " + testTable.getName(),
                    "VALUES (123456789012345678901234567890.12345679), (-123456789012345678901234567890.12345679)");
            assertQuery(
                    sessionWithDecimalMappingAllowOverflow(HALF_UP, 22),
                    format("SELECT column_name, data_type FROM information_schema.columns WHERE table_schema = 'tpch' AND table_schema||'.'||table_name = '%s'", testTable.getName()),
                    "VALUES ('d_col', 'decimal(38,20)')");
            assertQueryFails(
                    sessionWithDecimalMappingAllowOverflow(HALF_UP, 20),
                    "SELECT d_col FROM " + testTable.getName(),
                    "Decimal overflow");
            assertQueryFails(
                    sessionWithDecimalMappingAllowOverflow(HALF_UP, 9),
                    "SELECT d_col FROM " + testTable.getName(),
                    "Decimal overflow");
            assertQuery(
                    sessionWithDecimalMappingStrict(CONVERT_TO_VARCHAR),
                    format("SELECT column_name, data_type FROM information_schema.columns WHERE table_schema = 'tpch' AND table_schema||'.'||table_name = '%s'", testTable.getName()),
                    "VALUES ('d_col', 'varchar')");
            assertQuery(
                    sessionWithDecimalMappingStrict(CONVERT_TO_VARCHAR),
                    "SELECT d_col FROM " + testTable.getName(),
                    "VALUES ('123456789012345678901234567890.12345678901234500000'), ('-123456789012345678901234567890.12345678901234500000')");
        }
    }

    @Test(dataProvider = "testDecimalExceedingPrecisionMaxProvider")
    public void testDecimalExceedingPrecisionMaxWithSupportedValues(int typePrecision, int typeScale)
    {
        try (TestTable testTable = new TestTable(
                memSqlServer::execute,
                "tpch.test_exceeding_max_decimal",
                format("(d_col decimal(%d,%d))", typePrecision, typeScale),
                asList("12.01", "-12.01", "123", "-123", "1.12345678", "-1.12345678"))) {
            assertQuery(
                    sessionWithDecimalMappingAllowOverflow(UNNECESSARY, 0),
                    format("SELECT column_name, data_type FROM information_schema.columns WHERE table_schema = 'tpch' AND table_schema||'.'||table_name = '%s'", testTable.getName()),
                    "VALUES ('d_col', 'decimal(38,0)')");
            assertQueryFails(
                    sessionWithDecimalMappingAllowOverflow(UNNECESSARY, 0),
                    "SELECT d_col FROM " + testTable.getName(),
                    "Rounding necessary");
            assertQuery(
                    sessionWithDecimalMappingAllowOverflow(HALF_UP, 0),
                    "SELECT d_col FROM " + testTable.getName(),
                    "VALUES (12), (-12), (123), (-123), (1), (-1)");
            assertQuery(
                    sessionWithDecimalMappingAllowOverflow(HALF_UP, 3),
                    format("SELECT column_name, data_type FROM information_schema.columns WHERE table_schema = 'tpch' AND table_schema||'.'||table_name = '%s'", testTable.getName()),
                    "VALUES ('d_col', 'decimal(38,3)')");
            assertQuery(
                    sessionWithDecimalMappingAllowOverflow(HALF_UP, 3),
                    "SELECT d_col FROM " + testTable.getName(),
                    "VALUES (12.01), (-12.01), (123), (-123), (1.123), (-1.123)");
            assertQueryFails(
                    sessionWithDecimalMappingAllowOverflow(UNNECESSARY, 3),
                    "SELECT d_col FROM " + testTable.getName(),
                    "Rounding necessary");
            assertQuery(
                    sessionWithDecimalMappingAllowOverflow(HALF_UP, 8),
                    format("SELECT column_name, data_type FROM information_schema.columns WHERE table_schema = 'tpch' AND table_schema||'.'||table_name = '%s'", testTable.getName()),
                    "VALUES ('d_col', 'decimal(38,8)')");
            assertQuery(
                    sessionWithDecimalMappingAllowOverflow(HALF_UP, 8),
                    "SELECT d_col FROM " + testTable.getName(),
                    "VALUES (12.01), (-12.01), (123), (-123), (1.12345678), (-1.12345678)");
            assertQuery(
                    sessionWithDecimalMappingAllowOverflow(HALF_UP, 9),
                    "SELECT d_col FROM " + testTable.getName(),
                    "VALUES (12.01), (-12.01), (123), (-123), (1.12345678), (-1.12345678)");
            assertQuery(
                    sessionWithDecimalMappingAllowOverflow(UNNECESSARY, 8),
                    "SELECT d_col FROM " + testTable.getName(),
                    "VALUES (12.01), (-12.01), (123), (-123), (1.12345678), (-1.12345678)");
        }
    }

    @DataProvider
    public Object[][] testDecimalExceedingPrecisionMaxProvider()
    {
        return new Object[][] {
                {40, 8},
                {50, 10},
        };
    }

    private Session sessionWithDecimalMappingAllowOverflow(RoundingMode roundingMode, int scale)
    {
        return Session.builder(getSession())
                .setCatalogSessionProperty("memsql", DECIMAL_MAPPING, ALLOW_OVERFLOW.name())
                .setCatalogSessionProperty("memsql", DECIMAL_ROUNDING_MODE, roundingMode.name())
                .setCatalogSessionProperty("memsql", DECIMAL_DEFAULT_SCALE, Integer.valueOf(scale).toString())
                .build();
    }

    private Session sessionWithDecimalMappingStrict(UnsupportedTypeHandling unsupportedTypeHandling)
    {
        return Session.builder(getSession())
                .setCatalogSessionProperty("memsql", DECIMAL_MAPPING, STRICT.name())
                .setCatalogSessionProperty("memsql", UNSUPPORTED_TYPE_HANDLING, unsupportedTypeHandling.name())
                .build();
    }

    @Test
    public void testTrinoCreatedParameterizedChar()
    {
        memSqlCharTypeTest()
                .execute(getQueryRunner(), trinoCreateAsSelect("memsql_test_parameterized_char"));
    }

    @Test
    public void testMemSqlCreatedParameterizedChar()
    {
        memSqlCharTypeTest()
                .execute(getQueryRunner(), memSqlCreateAndInsert("tpch.memsql_test_parameterized_char"));
    }

    private DataTypeTest memSqlCharTypeTest()
    {
        return DataTypeTest.create()
                .addRoundTrip(charDataType("char", 1), "")
                .addRoundTrip(charDataType("char", 1), "a")
                .addRoundTrip(charDataType(1), "")
                .addRoundTrip(charDataType(1), "a")
                .addRoundTrip(charDataType(8), "abc")
                .addRoundTrip(charDataType(8), "12345678")
                .addRoundTrip(charDataType(255), "a".repeat(255));
    }

    @Test
    public void testMemSqlCreatedParameterizedCharUnicode()
    {
        DataTypeTest.create()
                .addRoundTrip(charDataType(1, CHARACTER_SET_UTF8), "\u653b")
                .addRoundTrip(charDataType(5, CHARACTER_SET_UTF8), "\u653b\u6bbb")
                .addRoundTrip(charDataType(5, CHARACTER_SET_UTF8), "\u653b\u6bbb\u6a5f\u52d5\u968a")
                .execute(getQueryRunner(), memSqlCreateAndInsert("tpch.memsql_test_parameterized_varchar"));
    }

    @Test
    public void testTrinoCreatedParameterizedVarchar()
    {
        DataTypeTest.create()
                .addRoundTrip(stringDataType("varchar(10)", createVarcharType(10)), "text_a")
                .addRoundTrip(stringDataType("varchar(255)", createVarcharType(255)), "text_b")
                .addRoundTrip(stringDataType("varchar(256)", createVarcharType(256)), "text_c")
                .addRoundTrip(stringDataType("varchar(" + MEMSQL_VARCHAR_MAX_LENGTH + ")", createVarcharType(MEMSQL_VARCHAR_MAX_LENGTH)), "text_memsql_max")
                // types larger than max VARCHAR(n) for MemSQL get mapped to one of TEXT/MEDIUMTEXT/LONGTEXT
                .addRoundTrip(stringDataType("varchar(" + (MEMSQL_VARCHAR_MAX_LENGTH + 1) + ")", createVarcharType(65535)), "text_memsql_larger_than_max")
                .addRoundTrip(stringDataType("varchar(65535)", createVarcharType(65535)), "text_d")
                .addRoundTrip(stringDataType("varchar(65536)", createVarcharType(16777215)), "text_e")
                .addRoundTrip(stringDataType("varchar(16777215)", createVarcharType(16777215)), "text_f")
                .addRoundTrip(stringDataType("varchar(16777216)", createUnboundedVarcharType()), "text_g")
                .addRoundTrip(stringDataType("varchar(" + VarcharType.MAX_LENGTH + ")", createUnboundedVarcharType()), "text_h")
                .addRoundTrip(varcharDataType(), "unbounded")
                .execute(getQueryRunner(), trinoCreateAsSelect("trino_test_parameterized_varchar"));
    }

    @Test
    public void testMemSqlCreatedParameterizedVarchar()
    {
        DataTypeTest.create()
                .addRoundTrip(stringDataType("tinytext", createVarcharType(255)), "a")
                .addRoundTrip(stringDataType("text", createVarcharType(65535)), "b")
                .addRoundTrip(stringDataType("mediumtext", createVarcharType(16777215)), "c")
                .addRoundTrip(stringDataType("longtext", createUnboundedVarcharType()), "d")
                .addRoundTrip(varcharDataType(32), "e")
                .addRoundTrip(varcharDataType(15000), "f")
                .execute(getQueryRunner(), memSqlCreateAndInsert("tpch.memsql_test_parameterized_varchar"));
    }

    @Test
    public void testMemSqlCreatedParameterizedVarcharUnicode()
    {
        String sampleUnicodeText = "\u653b\u6bbb\u6a5f\u52d5\u968a";
        DataTypeTest.create()
                .addRoundTrip(stringDataType("tinytext " + CHARACTER_SET_UTF8, createVarcharType(255)), sampleUnicodeText)
                .addRoundTrip(stringDataType("text " + CHARACTER_SET_UTF8, createVarcharType(65535)), sampleUnicodeText)
                .addRoundTrip(stringDataType("mediumtext " + CHARACTER_SET_UTF8, createVarcharType(16777215)), sampleUnicodeText)
                .addRoundTrip(stringDataType("longtext " + CHARACTER_SET_UTF8, createUnboundedVarcharType()), sampleUnicodeText)
                .addRoundTrip(varcharDataType(sampleUnicodeText.length(), CHARACTER_SET_UTF8), sampleUnicodeText)
                .addRoundTrip(varcharDataType(32, CHARACTER_SET_UTF8), sampleUnicodeText)
                .addRoundTrip(varcharDataType(20000, CHARACTER_SET_UTF8), sampleUnicodeText)
                .execute(getQueryRunner(), memSqlCreateAndInsert("tpch.memsql_test_parameterized_varchar_unicode"));
    }

    @Test
    public void testVarbinary()
    {
        varbinaryTestCases("varbinary(50)")
                .execute(getQueryRunner(), memSqlCreateAndInsert("tpch.test_varbinary"));

        varbinaryTestCases("tinyblob")
                .execute(getQueryRunner(), memSqlCreateAndInsert("tpch.test_varbinary"));

        varbinaryTestCases("blob")
                .execute(getQueryRunner(), memSqlCreateAndInsert("tpch.test_varbinary"));

        varbinaryTestCases("mediumblob")
                .execute(getQueryRunner(), memSqlCreateAndInsert("tpch.test_varbinary"));

        varbinaryTestCases("longblob")
                .execute(getQueryRunner(), memSqlCreateAndInsert("tpch.test_varbinary"));

        varbinaryTestCases("varbinary")
                .execute(getQueryRunner(), trinoCreateAsSelect("test_varbinary"));
    }

    private SqlDataTypeTest varbinaryTestCases(String insertType)
    {
        return SqlDataTypeTest.create()
                .addRoundTrip(insertType, "NULL", VARBINARY, "CAST(NULL AS varbinary)")
                .addRoundTrip(insertType, "X''", VARBINARY, "X''")
                .addRoundTrip(insertType, "X'68656C6C6F'", VARBINARY, "to_utf8('hello')")
                .addRoundTrip(insertType, "X'5069C4996B6E6120C582C4856B61207720E69DB1E4BAACE983BD'", VARBINARY, "to_utf8('Piękna łąka w 東京都')")
                .addRoundTrip(insertType, "X'4261672066756C6C206F6620F09F92B0'", VARBINARY, "to_utf8('Bag full of 💰')")
                .addRoundTrip(insertType, "X'0001020304050607080DF9367AA7000000'", VARBINARY, "X'0001020304050607080DF9367AA7000000'") // non-text
                .addRoundTrip(insertType, "X'000000000000'", VARBINARY, "X'000000000000'");
    }

    @Test
    public void testBinary()
    {
        SqlDataTypeTest.create()
                .addRoundTrip("binary(18)", "NULL", VARBINARY, "CAST(NULL AS varbinary)")
                .addRoundTrip("binary(18)", "X''", VARBINARY, "X'000000000000000000000000000000000000'")
                .addRoundTrip("binary(18)", "X'68656C6C6F'", VARBINARY, "to_utf8('hello') || X'00000000000000000000000000'")
                .addRoundTrip("binary(18)", "X'C582C4856B61207720E69DB1E4BAACE983BD'", VARBINARY, "to_utf8('łąka w 東京都')") // no trailing zeros
                .addRoundTrip("binary(18)", "X'4261672066756C6C206F6620F09F92B0'", VARBINARY, "to_utf8('Bag full of 💰') || X'0000'")
                .addRoundTrip("binary(18)", "X'0001020304050607080DF9367AA7000000'", VARBINARY, "X'0001020304050607080DF9367AA700000000'") // non-text prefix
                .addRoundTrip("binary(18)", "X'000000000000'", VARBINARY, "X'000000000000000000000000000000000000'")
                .execute(getQueryRunner(), memSqlCreateAndInsert("tpch.test_binary"));
    }

    @Test
    public void testDate()
    {
        ZoneId jvmZone = ZoneId.systemDefault();
        checkState(jvmZone.getId().equals("America/Bahia_Banderas"), "This test assumes certain JVM time zone");

        ZoneId someZone = ZoneId.of("Europe/Vilnius");

        for (String timeZoneId : ImmutableList.of(UTC_KEY.getId(), jvmZone.getId(), someZone.getId())) {
            Session session = Session.builder(getSession())
                    .setTimeZoneKey(TimeZoneKey.getTimeZoneKey(timeZoneId))
                    .build();
            dateTestCases(memSqlDateDataType(value -> formatStringLiteral(value.toString())), jvmZone, someZone)
                    .execute(getQueryRunner(), session, memSqlCreateAndInsert("tpch.test_date"));
            dateTestCases(dateDataType(), jvmZone, someZone)
                    .execute(getQueryRunner(), session, trinoCreateAsSelect(session, "test_date"));
            dateTestCases(dateDataType(), jvmZone, someZone)
                    .execute(getQueryRunner(), session, trinoCreateAsSelect(getSession(), "test_date"));
            dateTestCases(dateDataType(), jvmZone, someZone)
                    .execute(getQueryRunner(), session, trinoCreateAndInsert(session, "test_date"));
        }
    }

    private DataTypeTest dateTestCases(DataType<LocalDate> dateDataType, ZoneId jvmZone, ZoneId someZone)
    {
        LocalDate dateOfLocalTimeChangeForwardAtMidnightInJvmZone = LocalDate.of(1970, 1, 1);
        verify(jvmZone.getRules().getValidOffsets(dateOfLocalTimeChangeForwardAtMidnightInJvmZone.atStartOfDay()).isEmpty());

        LocalDate dateOfLocalTimeChangeForwardAtMidnightInSomeZone = LocalDate.of(1983, 4, 1);
        verify(someZone.getRules().getValidOffsets(dateOfLocalTimeChangeForwardAtMidnightInSomeZone.atStartOfDay()).isEmpty());
        LocalDate dateOfLocalTimeChangeBackwardAtMidnightInSomeZone = LocalDate.of(1983, 10, 1);
        verify(someZone.getRules().getValidOffsets(dateOfLocalTimeChangeBackwardAtMidnightInSomeZone.atStartOfDay().minusMinutes(1)).size() == 2);

        return DataTypeTest.create()
                .addRoundTrip(dateDataType, LocalDate.of(1, 1, 1))
                .addRoundTrip(dateDataType, LocalDate.of(1582, 10, 4)) // before julian->gregorian switch
                .addRoundTrip(dateDataType, LocalDate.of(1582, 10, 5)) // begin julian->gregorian switch
                .addRoundTrip(dateDataType, LocalDate.of(1582, 10, 14)) // end julian->gregorian switch
                .addRoundTrip(dateDataType, LocalDate.of(1952, 4, 3)) // before epoch
                .addRoundTrip(dateDataType, LocalDate.of(1970, 1, 1))
                .addRoundTrip(dateDataType, LocalDate.of(1970, 2, 3))
                .addRoundTrip(dateDataType, LocalDate.of(2017, 7, 1)) // summer on northern hemisphere (possible DST)
                .addRoundTrip(dateDataType, LocalDate.of(2017, 1, 1)) // winter on northern hemisphere (possible DST on southern hemisphere)
                .addRoundTrip(dateDataType, dateOfLocalTimeChangeForwardAtMidnightInJvmZone)
                .addRoundTrip(dateDataType, dateOfLocalTimeChangeForwardAtMidnightInSomeZone)
                .addRoundTrip(dateDataType, dateOfLocalTimeChangeBackwardAtMidnightInSomeZone);
    }

    @Test(dataProvider = "sessionZonesDataProvider")
    public void testDatetime(ZoneId sessionZone)
    {
        Session session = Session.builder(getSession())
                .setTimeZoneKey(getTimeZoneKey(sessionZone.getId()))
                .build();

        // TODO (https://github.com/trinodb/trino/issues/5450) Fix DST handling
        SqlDataTypeTest.create()
                // before epoch
                .addRoundTrip("datetime", "CAST('1958-01-01 13:18:03' AS DATETIME)", createTimestampType(0), "TIMESTAMP '1958-01-01 13:18:03'")
                // after epoch
                .addRoundTrip("datetime", "CAST('2019-03-18 10:01:17' AS DATETIME)", createTimestampType(0), "TIMESTAMP '2019-03-18 10:01:17'")
                // time doubled in JVM zone
                .addRoundTrip("datetime", "CAST('2018-10-28 01:33:17' AS DATETIME)", createTimestampType(0), "TIMESTAMP '2018-10-28 01:33:17'")
                // time double in Vilnius
                .addRoundTrip("datetime", "CAST('2018-10-28 03:33:33' AS DATETIME)", createTimestampType(0), "TIMESTAMP '2018-10-28 03:33:33'")
                // epoch
//                .addRoundTrip("datetime", "CAST('1970-01-01 00:00:00' AS DATETIME)", createTimestampType(0), "TIMESTAMP '1970-01-01 00:00:00'")
//                .addRoundTrip("datetime", "CAST('1970-01-01 00:13:42' AS DATETIME)", createTimestampType(0), "TIMESTAMP '1970-01-01 00:13:42'")
//                .addRoundTrip("datetime", "CAST('2018-04-01 02:13:55' AS DATETIME)", createTimestampType(0), "TIMESTAMP '2018-04-01 02:13:55'")
                // time gap in Vilnius
                .addRoundTrip("datetime", "CAST('2018-03-25 03:17:17.000000' AS DATETIME)", createTimestampType(0), "TIMESTAMP '2018-03-25 03:17:17'")
                // time gap in Kathmandu
                .addRoundTrip("datetime", "CAST('1986-01-01 00:13:07.000000' AS DATETIME)", createTimestampType(0), "TIMESTAMP '1986-01-01 00:13:07'")

                // same as above but with higher precision
                .addRoundTrip("datetime(6)", "CAST('1958-01-01 13:18:03.123456' AS DATETIME(6))", createTimestampType(6), "TIMESTAMP '1958-01-01 13:18:03.123456'")
                .addRoundTrip("datetime(6)", "CAST('2019-03-18 10:01:17.987654' AS DATETIME(6))", createTimestampType(6), "TIMESTAMP '2019-03-18 10:01:17.987654'")
                .addRoundTrip("datetime(6)", "CAST('2018-10-28 01:33:17.456789' AS DATETIME(6))", createTimestampType(6), "TIMESTAMP '2018-10-28 01:33:17.456789'")
                .addRoundTrip("datetime(6)", "CAST('2018-10-28 03:33:33.333333' AS DATETIME(6))", createTimestampType(6), "TIMESTAMP '2018-10-28 03:33:33.333333'")
//                .addRoundTrip("datetime(6)", "CAST('1970-01-01 00:00:00.000000' AS DATETIME(6))", createTimestampType(6), "TIMESTAMP '1970-01-01 00:00:00.000000'")
//                .addRoundTrip("datetime(6)", "CAST('1970-01-01 00:13:42.000001' AS DATETIME(6))", createTimestampType(6), "TIMESTAMP '1970-01-01 00:13:42.000001'")
//                .addRoundTrip("datetime(6)", "CAST('2018-04-01 02:13:55.123456' AS DATETIME(6))", createTimestampType(6), "TIMESTAMP '2018-04-01 02:13:55.123456'")
                .addRoundTrip("datetime(6)", "CAST('2018-03-25 03:17:17.000000' AS DATETIME(6))", createTimestampType(6), "TIMESTAMP '2018-03-25 03:17:17.000000'")
                .addRoundTrip("datetime(6)", "CAST('1986-01-01 00:13:07.000000' AS DATETIME(6))", createTimestampType(6), "TIMESTAMP '1986-01-01 00:13:07.000000'")

                // negative epoch
                .addRoundTrip("datetime(6)", "CAST('1969-12-31 23:59:59.999995' AS DATETIME(6))", createTimestampType(6), "TIMESTAMP '1969-12-31 23:59:59.999995'")
                .addRoundTrip("datetime(6)", "CAST('1969-12-31 23:59:59.999949' AS DATETIME(6))", createTimestampType(6), "TIMESTAMP '1969-12-31 23:59:59.999949'")
                .addRoundTrip("datetime(6)", "CAST('1969-12-31 23:59:59.999994' AS DATETIME(6))", createTimestampType(6), "TIMESTAMP '1969-12-31 23:59:59.999994'")

                // min value in MemSQL
                .addRoundTrip("datetime", "CAST('1000-01-01 00:00:00' AS DATETIME)", createTimestampType(0), "TIMESTAMP '1000-01-01 00:00:00'")
                .addRoundTrip("datetime(6)", "CAST('1000-01-01 00:00:00.000000' AS DATETIME(6))", createTimestampType(6), "TIMESTAMP '1000-01-01 00:00:00.000000'")

                // max value in MemSQL
                .addRoundTrip("datetime", "CAST('9999-12-31 23:59:59' AS DATETIME)", createTimestampType(0), "TIMESTAMP '9999-12-31 23:59:59'")
                .addRoundTrip("datetime(6)", "CAST('9999-12-31 23:59:59.999999' AS DATETIME(6))", createTimestampType(6), "TIMESTAMP '9999-12-31 23:59:59.999999'")

                // null
                .addRoundTrip("datetime", "NULL", createTimestampType(0), "CAST(NULL AS TIMESTAMP(0))")
                .addRoundTrip("datetime(6)", "NULL", createTimestampType(6), "CAST(NULL AS TIMESTAMP(6))")

                .execute(getQueryRunner(), session, memSqlCreateAndInsert("tpch.test_datetime"));
    }

    @Test(dataProvider = "sessionZonesDataProvider")
    public void testTimestamp(ZoneId sessionZone)
    {
        Session session = Session.builder(getSession())
                .setTimeZoneKey(getTimeZoneKey(sessionZone.getId()))
                .build();

        // TODO (https://github.com/trinodb/trino/issues/5450) Fix DST handling
        SqlDataTypeTest.create()
                // before epoch doesn't exist because min timestamp value is 1970-01-01 in MemSQL
                // after epoch
                .addRoundTrip("timestamp", toTimestamp("2019-03-18 10:01:17"), createTimestampType(0), "TIMESTAMP '2019-03-18 10:01:17'")
                // time doubled in JVM zone
                .addRoundTrip("timestamp", toTimestamp("2018-10-28 01:33:17"), createTimestampType(0), "TIMESTAMP '2018-10-28 01:33:17'")
                // time double in Vilnius
                .addRoundTrip("timestamp", toTimestamp("2018-10-28 03:33:33"), createTimestampType(0), "TIMESTAMP '2018-10-28 03:33:33'")
                // epoch
//                .addRoundTrip("timestamp", toTimestamp("1970-01-01 00:00:00"), createTimestampType(0), "TIMESTAMP '1970-01-01 00:00:00'")
//                .addRoundTrip("timestamp", toTimestamp("1970-01-01 00:13:42"), createTimestampType(0), "TIMESTAMP '1970-01-01 00:13:42'")
//                .addRoundTrip("timestamp", toTimestamp("2018-04-01 02:13:55"), createTimestampType(0), "TIMESTAMP '2018-04-01 02:13:55'")
                // time gap in Vilnius
                .addRoundTrip("timestamp", toTimestamp("2018-03-25 03:17:17.000000"), createTimestampType(0), "TIMESTAMP '2018-03-25 03:17:17'")

                // same as above but with higher precision
                .addRoundTrip("timestamp(6)", toLongTimestamp("2019-03-18 10:01:17.987654"), createTimestampType(6), "TIMESTAMP '2019-03-18 10:01:17.987654'")
                .addRoundTrip("timestamp(6)", toLongTimestamp("2018-10-28 01:33:17.456789"), createTimestampType(6), "TIMESTAMP '2018-10-28 01:33:17.456789'")
                .addRoundTrip("timestamp(6)", toLongTimestamp("2018-10-28 03:33:33.333333"), createTimestampType(6), "TIMESTAMP '2018-10-28 03:33:33.333333'")
//                .addRoundTrip("timestamp(6)", toLongTimestamp("1970-01-01 00:00:00.000000"), createTimestampType(6), "TIMESTAMP '1970-01-01 00:00:00.000000'")
//                .addRoundTrip("timestamp(6)", toLongTimestamp("1970-01-01 00:13:42.000001"), createTimestampType(6), "TIMESTAMP '1970-01-01 00:13:42.000001'")
//                .addRoundTrip("timestamp(6)", toLongTimestamp("2018-04-01 02:13:55.123456"), createTimestampType(6), "TIMESTAMP '2018-04-01 02:13:55.123456'")
                .addRoundTrip("timestamp(6)", toLongTimestamp("2018-03-25 03:17:17.000000"), createTimestampType(6), "TIMESTAMP '2018-03-25 03:17:17.000000'")

                // min value in MemSQL
//                .addRoundTrip("timestamp", toTimestamp("1970-01-01 00:00:01"), createTimestampType(0), "TIMESTAMP '1970-01-01 00:00:01'")
//                .addRoundTrip("timestamp(6)", toLongTimestamp("1970-01-01 00:00:01.000000"), createTimestampType(6), "TIMESTAMP '1970-01-01 00:00:01.000000'")

                // max value in MemSQL
                .addRoundTrip("timestamp", toTimestamp("2038-01-19 03:14:07"), createTimestampType(0), "TIMESTAMP '2038-01-19 03:14:07'")
                .addRoundTrip("timestamp(6)", toLongTimestamp("2038-01-19 03:14:07.999999"), createTimestampType(6), "TIMESTAMP '2038-01-19 03:14:07.999999'")

                // null
                .addRoundTrip("timestamp", "NULL", createTimestampType(0), "CAST(NULL AS TIMESTAMP(0))")
                .addRoundTrip("timestamp(6)", "NULL", createTimestampType(6), "CAST(NULL AS TIMESTAMP(6))")

                .execute(getQueryRunner(), session, memSqlCreateAndInsert("tpch.test_timestamp"));
    }

    @Test(dataProvider = "sessionZonesDataProvider")
    public void testTimestampWrite(ZoneId sessionZone)
    {
        Session session = Session.builder(getSession())
                .setTimeZoneKey(getTimeZoneKey(sessionZone.getId()))
                .build();

        // TODO (https://github.com/trinodb/trino/issues/5450) Fix DST handling
        SqlDataTypeTest.create()
                // without precision
                .addRoundTrip("TIMESTAMP '2021-10-21 12:34:56.123456'", "TIMESTAMP '2021-10-21 12:34:56.123456'")
                // before epoch
                .addRoundTrip("timestamp(0)", "TIMESTAMP '1958-01-01 13:18:03'", createTimestampType(0), "TIMESTAMP '1958-01-01 13:18:03'")
                // after epoch
                .addRoundTrip("timestamp(0)", "TIMESTAMP '2019-03-18 10:01:17'", createTimestampType(0), "TIMESTAMP '2019-03-18 10:01:17'")
                // time doubled in JVM zone
                .addRoundTrip("timestamp(0)", "TIMESTAMP '2018-10-28 01:33:17'", createTimestampType(0), "TIMESTAMP '2018-10-28 01:33:17'")
                // time double in Vilnius
                .addRoundTrip("timestamp(0)", "TIMESTAMP '2018-10-28 03:33:33'", createTimestampType(0), "TIMESTAMP '2018-10-28 03:33:33'")
                // epoch
//                .addRoundTrip("timestamp(0)", "TIMESTAMP '1970-01-01 00:00:00'", createTimestampType(0), "TIMESTAMP '1970-01-01 00:00:00'")
//                .addRoundTrip("timestamp(0)", "TIMESTAMP '1970-01-01 00:13:42'", createTimestampType(0), "TIMESTAMP '1970-01-01 00:13:42'")
//                .addRoundTrip("timestamp(0)", "TIMESTAMP '2018-04-01 02:13:55'", createTimestampType(0), "TIMESTAMP '2018-04-01 02:13:55'")
                // time gap in Vilnius
                .addRoundTrip("timestamp(0)", "TIMESTAMP '2018-03-25 03:17:17.000000'", createTimestampType(0), "TIMESTAMP '2018-03-25 03:17:17'")
                // time gap in Kathmandu
                .addRoundTrip("timestamp(0)", "TIMESTAMP '1986-01-01 00:13:07.000000'", createTimestampType(0), "TIMESTAMP '1986-01-01 00:13:07'")

                // same as above but with higher precision
                .addRoundTrip("timestamp(6)", "TIMESTAMP '1958-01-01 13:18:03.123456'", createTimestampType(6), "TIMESTAMP '1958-01-01 13:18:03.123456'")
                .addRoundTrip("timestamp(6)", "TIMESTAMP '2019-03-18 10:01:17.987654'", createTimestampType(6), "TIMESTAMP '2019-03-18 10:01:17.987654'")
                .addRoundTrip("timestamp(6)", "TIMESTAMP '2018-10-28 01:33:17.456789'", createTimestampType(6), "TIMESTAMP '2018-10-28 01:33:17.456789'")
                .addRoundTrip("timestamp(6)", "TIMESTAMP '2018-10-28 03:33:33.333333'", createTimestampType(6), "TIMESTAMP '2018-10-28 03:33:33.333333'")
//                .addRoundTrip("timestamp(6)", "TIMESTAMP '1970-01-01 00:00:00.000000'", createTimestampType(6), "TIMESTAMP '1970-01-01 00:00:00.000000'")
//                .addRoundTrip("timestamp(6)", "TIMESTAMP '1970-01-01 00:13:42.000001'", createTimestampType(6), "TIMESTAMP '1970-01-01 00:13:42.000001'")
//                .addRoundTrip("timestamp(6)", "TIMESTAMP '2018-04-01 02:13:55.123456'", createTimestampType(6), "TIMESTAMP '2018-04-01 02:13:55.123456'")
                .addRoundTrip("timestamp(6)", "TIMESTAMP '2018-03-25 03:17:17.000000'", createTimestampType(6), "TIMESTAMP '2018-03-25 03:17:17.000000'")
                .addRoundTrip("timestamp(6)", "TIMESTAMP '1986-01-01 00:13:07.000000'", createTimestampType(6), "TIMESTAMP '1986-01-01 00:13:07.000000'")

                // negative epoch
                .addRoundTrip("timestamp(6)", "TIMESTAMP '1969-12-31 23:59:59.999995'", createTimestampType(6), "TIMESTAMP '1969-12-31 23:59:59.999995'")
                .addRoundTrip("timestamp(6)", "TIMESTAMP '1969-12-31 23:59:59.999949'", createTimestampType(6), "TIMESTAMP '1969-12-31 23:59:59.999949'")
                .addRoundTrip("timestamp(6)", "TIMESTAMP '1969-12-31 23:59:59.999994'", createTimestampType(6), "TIMESTAMP '1969-12-31 23:59:59.999994'")

                // min value in MemSQL
                .addRoundTrip("timestamp(0)", "TIMESTAMP '1000-01-01 00:00:00'", createTimestampType(0), "TIMESTAMP '1000-01-01 00:00:00'")
                .addRoundTrip("timestamp(6)", "TIMESTAMP '1000-01-01 00:00:00.000000'", createTimestampType(6), "TIMESTAMP '1000-01-01 00:00:00.000000'")

                // max value in MemSQL
                .addRoundTrip("timestamp(0)", "TIMESTAMP '9999-12-31 23:59:59'", createTimestampType(0), "TIMESTAMP '9999-12-31 23:59:59'")
                .addRoundTrip("timestamp(6)", "TIMESTAMP '9999-12-31 23:59:59.999999'", createTimestampType(6), "TIMESTAMP '9999-12-31 23:59:59.999999'")

                // null
                .addRoundTrip("timestamp(0)", "NULL", createTimestampType(0), "CAST(NULL AS TIMESTAMP(0))")
                .addRoundTrip("timestamp(6)", "NULL", createTimestampType(6), "CAST(NULL AS TIMESTAMP(6))")

                .execute(getQueryRunner(), session, trinoCreateAsSelect(session, "tpch.test_datetime"))
                .execute(getQueryRunner(), session, trinoCreateAndInsert(session, "tpch.test_datetime"));
    }

    @DataProvider
    public Object[][] sessionZonesDataProvider()
    {
        return new Object[][] {
                {UTC},
                {ZoneId.systemDefault()},
                // no DST in 1970, but has DST in later years (e.g. 2018)
                {ZoneId.of("Europe/Vilnius")},
                // minutes offset change since 1970-01-01, no DST
                {ZoneId.of("Asia/Kathmandu")},
                {ZoneId.of(TestingSession.DEFAULT_TIME_ZONE_KEY.getId())},
        };
    }

    @Test(dataProvider = "unsupportedDateTimePrecisions")
    public void testUnsupportedDateTimePrecision(int precision)
    {
        // This test should be fixed if future MemSQL supports those precisions
        assertThatThrownBy(() -> memSqlServer.execute(format("CREATE TABLE test_unsupported_timestamp_precision (col1 TIMESTAMP(%s))", precision)))
                .hasMessageContaining("Feature 'TIMESTAMP type with precision other than 0 or 6' is not supported by MemSQL.");

        assertThatThrownBy(() -> memSqlServer.execute(format("CREATE TABLE test_unsupported_datetime_precision (col1 DATETIME(%s))", precision)))
                .hasMessageContaining("Feature 'DATETIME type with precision other than 0 or 6' is not supported by MemSQL.");
    }

    @DataProvider
    public Object[][] unsupportedDateTimePrecisions()
    {
        return new Object[][] {
                {1},
                {2},
                {3},
                {4},
                {5},
                {7},
                {8},
                {9},
        };
    }

    @Test
    public void testJson()
    {
        jsonTestCases(memSqlJsonDataType(value -> "JSON " + formatStringLiteral(value)))
                .execute(getQueryRunner(), trinoCreateAsSelect("trino_test_json"));
        // MemSQL doesn't support CAST to JSON but accepts string literals as JSON values
        jsonTestCases(memSqlJsonDataType(value -> format("%s", formatStringLiteral(value))))
                .execute(getQueryRunner(), memSqlCreateAndInsert("tpch.mysql_test_json"));
    }

    private DataTypeTest jsonTestCases(DataType<String> jsonDataType)
    {
        return DataTypeTest.create()
                .addRoundTrip(jsonDataType, "{}")
                .addRoundTrip(jsonDataType, null)
                .addRoundTrip(jsonDataType, "null")
                .addRoundTrip(jsonDataType, "123.4")
                .addRoundTrip(jsonDataType, "\"abc\"")
                .addRoundTrip(jsonDataType, "\"text with ' apostrophes\"")
                .addRoundTrip(jsonDataType, "\"\"")
                .addRoundTrip(jsonDataType, "{\"a\":1,\"b\":2}")
                .addRoundTrip(jsonDataType, "{\"a\":[1,2,3],\"b\":{\"aa\":11,\"bb\":[{\"a\":1,\"b\":2},{\"a\":0}]}}")
                .addRoundTrip(jsonDataType, "[]");
    }

    private void testUnsupportedDataType(String databaseDataType)
    {
        SqlExecutor jdbcSqlExecutor = memSqlServer::execute;
        jdbcSqlExecutor.execute(format("CREATE TABLE tpch.test_unsupported_data_type(supported_column varchar(5), unsupported_column %s)", databaseDataType));
        try {
            assertQuery(
                    "SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = 'tpch' AND TABLE_NAME = 'test_unsupported_data_type'",
                    "VALUES 'supported_column'"); // no 'unsupported_column'
        }
        finally {
            jdbcSqlExecutor.execute("DROP TABLE tpch.test_unsupported_data_type");
        }
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

    private DataSetup memSqlCreateAndInsert(String tableNamePrefix)
    {
        return new CreateAndInsertDataSetup(memSqlServer::execute, tableNamePrefix);
    }

    private static DataType<LocalDate> memSqlDateDataType(Function<LocalDate, String> toLiteral)
    {
        return dataType("date", DATE, toLiteral);
    }

    private static DataType<String> memSqlJsonDataType(Function<String, String> toLiteral)
    {
        return dataType("json", JSON, toLiteral);
    }

    private static DataType<Double> memSqlDoubleDataType()
    {
        return dataType("double precision", DOUBLE, Object::toString);
    }

    private static String toTimestamp(String value)
    {
        return format("TO_TIMESTAMP('%s', 'YYYY-MM-DD HH24:MI:SS')", value);
    }

    private static String toLongTimestamp(String value)
    {
        return format("TO_TIMESTAMP('%s', 'YYYY-MM-DD HH24:MI:SS.FF6')", value);
    }
}
