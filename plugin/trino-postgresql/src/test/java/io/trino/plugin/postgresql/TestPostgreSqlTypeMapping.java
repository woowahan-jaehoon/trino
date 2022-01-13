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
package io.trino.plugin.postgresql;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.airlift.json.JsonCodec;
import io.trino.Session;
import io.trino.plugin.jdbc.UnsupportedTypeHandling;
import io.trino.spi.type.ArrayType;
import io.trino.spi.type.CharType;
import io.trino.spi.type.Decimals;
import io.trino.spi.type.TimeZoneKey;
import io.trino.spi.type.Type;
import io.trino.sql.planner.plan.FilterNode;
import io.trino.testing.AbstractTestQueryFramework;
import io.trino.testing.DataProviders;
import io.trino.testing.QueryRunner;
import io.trino.testing.TestingSession;
import io.trino.testing.datatype.CreateAndInsertDataSetup;
import io.trino.testing.datatype.CreateAndTrinoInsertDataSetup;
import io.trino.testing.datatype.CreateAsSelectDataSetup;
import io.trino.testing.datatype.DataSetup;
import io.trino.testing.datatype.DataType;
import io.trino.testing.datatype.DataTypeTest;
import io.trino.testing.datatype.SqlDataTypeTest;
import io.trino.testing.sql.JdbcSqlExecutor;
import io.trino.testing.sql.TestTable;
import io.trino.testing.sql.TrinoSqlExecutor;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Stream;

import static com.google.common.base.Preconditions.checkState;
import static com.google.common.base.Verify.verify;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.io.BaseEncoding.base16;
import static io.airlift.json.JsonCodec.listJsonCodec;
import static io.airlift.json.JsonCodec.mapJsonCodec;
import static io.trino.plugin.jdbc.DecimalConfig.DecimalMapping.ALLOW_OVERFLOW;
import static io.trino.plugin.jdbc.DecimalConfig.DecimalMapping.STRICT;
import static io.trino.plugin.jdbc.DecimalSessionSessionProperties.DECIMAL_DEFAULT_SCALE;
import static io.trino.plugin.jdbc.DecimalSessionSessionProperties.DECIMAL_MAPPING;
import static io.trino.plugin.jdbc.DecimalSessionSessionProperties.DECIMAL_ROUNDING_MODE;
import static io.trino.plugin.jdbc.TypeHandlingJdbcSessionProperties.UNSUPPORTED_TYPE_HANDLING;
import static io.trino.plugin.jdbc.UnsupportedTypeHandling.CONVERT_TO_VARCHAR;
import static io.trino.plugin.jdbc.UnsupportedTypeHandling.IGNORE;
import static io.trino.plugin.postgresql.PostgreSqlConfig.ArrayMapping.AS_ARRAY;
import static io.trino.plugin.postgresql.PostgreSqlConfig.ArrayMapping.AS_JSON;
import static io.trino.plugin.postgresql.PostgreSqlConfig.ArrayMapping.DISABLED;
import static io.trino.plugin.postgresql.PostgreSqlQueryRunner.createPostgreSqlQueryRunner;
import static io.trino.spi.type.BigintType.BIGINT;
import static io.trino.spi.type.BooleanType.BOOLEAN;
import static io.trino.spi.type.CharType.createCharType;
import static io.trino.spi.type.DateType.DATE;
import static io.trino.spi.type.DecimalType.createDecimalType;
import static io.trino.spi.type.DoubleType.DOUBLE;
import static io.trino.spi.type.IntegerType.INTEGER;
import static io.trino.spi.type.RealType.REAL;
import static io.trino.spi.type.SmallintType.SMALLINT;
import static io.trino.spi.type.TimeZoneKey.UTC_KEY;
import static io.trino.spi.type.TimestampWithTimeZoneType.createTimestampWithTimeZoneType;
import static io.trino.spi.type.TypeSignature.mapType;
import static io.trino.spi.type.UuidType.UUID;
import static io.trino.spi.type.VarbinaryType.VARBINARY;
import static io.trino.spi.type.VarcharType.VARCHAR;
import static io.trino.spi.type.VarcharType.createVarcharType;
import static io.trino.testing.datatype.DataType.bigintDataType;
import static io.trino.testing.datatype.DataType.booleanDataType;
import static io.trino.testing.datatype.DataType.dataType;
import static io.trino.testing.datatype.DataType.dateDataType;
import static io.trino.testing.datatype.DataType.decimalDataType;
import static io.trino.testing.datatype.DataType.doubleDataType;
import static io.trino.testing.datatype.DataType.formatStringLiteral;
import static io.trino.testing.datatype.DataType.integerDataType;
import static io.trino.testing.datatype.DataType.jsonDataType;
import static io.trino.testing.datatype.DataType.realDataType;
import static io.trino.testing.datatype.DataType.smallintDataType;
import static io.trino.testing.datatype.DataType.timeDataType;
import static io.trino.testing.datatype.DataType.timestampDataType;
import static io.trino.testing.datatype.DataType.varcharDataType;
import static io.trino.type.JsonType.JSON;
import static java.lang.String.format;
import static java.math.RoundingMode.HALF_UP;
import static java.math.RoundingMode.UNNECESSARY;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.time.ZoneOffset.UTC;
import static java.util.Arrays.asList;
import static java.util.Collections.singletonList;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toList;
import static org.assertj.core.api.Assertions.assertThat;

public class TestPostgreSqlTypeMapping
        extends AbstractTestQueryFramework
{
    private static final LocalDate EPOCH_DAY = LocalDate.ofEpochDay(0);
    private static final JsonCodec<List<Map<String, String>>> HSTORE_CODEC = listJsonCodec(mapJsonCodec(String.class, String.class));

    protected TestingPostgreSqlServer postgreSqlServer;

    private final LocalDateTime beforeEpoch = LocalDateTime.of(1958, 1, 1, 13, 18, 3, 123_000_000);
    private final LocalDateTime epoch = LocalDateTime.of(1970, 1, 1, 0, 0, 0);
    private final LocalDateTime afterEpoch = LocalDateTime.of(2019, 3, 18, 10, 1, 17, 987_000_000);

    private final ZoneId jvmZone = ZoneId.systemDefault();
    private final LocalDateTime timeGapInJvmZone1 = LocalDateTime.of(1970, 1, 1, 0, 13, 42);
    private final LocalDateTime timeGapInJvmZone2 = LocalDateTime.of(2018, 4, 1, 2, 13, 55, 123_000_000);
    private final LocalDateTime timeDoubledInJvmZone = LocalDateTime.of(2018, 10, 28, 1, 33, 17, 456_000_000);

    // no DST in 1970, but has DST in later years (e.g. 2018)
    private final ZoneId vilnius = ZoneId.of("Europe/Vilnius");
    private final LocalDateTime timeGapInVilnius = LocalDateTime.of(2018, 3, 25, 3, 17, 17);
    private final LocalDateTime timeDoubledInVilnius = LocalDateTime.of(2018, 10, 28, 3, 33, 33, 333_000_000);

    // minutes offset change since 1970-01-01, no DST
    private final ZoneId kathmandu = ZoneId.of("Asia/Kathmandu");
    private final LocalDateTime timeGapInKathmandu = LocalDateTime.of(1986, 1, 1, 0, 13, 7);

    private final ZoneOffset fixedOffsetEast = ZoneOffset.ofHoursMinutes(2, 17);
    private final ZoneOffset fixedOffsetWest = ZoneOffset.ofHoursMinutes(-7, -31);

    @Override
    protected QueryRunner createQueryRunner()
            throws Exception
    {
        postgreSqlServer = closeAfterClass(new TestingPostgreSqlServer());
        return createPostgreSqlQueryRunner(
                postgreSqlServer,
                ImmutableMap.of(),
                ImmutableMap.of("jdbc-types-mapped-to-varchar", "Tsrange, Inet" /* make sure that types are compared case insensitively */),
                ImmutableList.of());
    }

    @BeforeClass
    public void setUp()
    {
        checkIsGap(jvmZone, timeGapInJvmZone1);
        checkIsGap(jvmZone, timeGapInJvmZone2);
        checkIsDoubled(jvmZone, timeDoubledInJvmZone);

        checkIsGap(vilnius, timeGapInVilnius);
        checkIsDoubled(vilnius, timeDoubledInVilnius);

        checkIsGap(kathmandu, timeGapInKathmandu);

        JdbcSqlExecutor executor = new JdbcSqlExecutor(postgreSqlServer.getJdbcUrl(), postgreSqlServer.getProperties());
        executor.execute("CREATE EXTENSION hstore WITH SCHEMA public");
    }

    @Test
    public void testBasicTypes()
    {
        SqlDataTypeTest.create()
                .addRoundTrip("boolean", "true", BOOLEAN)
                .addRoundTrip("boolean", "false", BOOLEAN)
                .addRoundTrip("bigint", "123456789012", BIGINT)
                .addRoundTrip("integer", "123456789", INTEGER)
                .addRoundTrip("smallint", "32456", SMALLINT, "SMALLINT '32456'")
                .addRoundTrip("tinyint", "5", SMALLINT, "SMALLINT '5'")
                .execute(getQueryRunner(), trinoCreateAsSelect("test_basic_types"));
    }

    @Test
    public void testReal()
    {
        SqlDataTypeTest.create()
                .addRoundTrip("real", "NULL", REAL, "CAST(NULL AS real)")
                .addRoundTrip("real", "3.14", REAL, "REAL '3.14'")
                .addRoundTrip("real", "3.1415927", REAL, "REAL '3.1415927'")
                .addRoundTrip("real", "'NaN'::real", REAL, "CAST(nan() AS real)")
                .addRoundTrip("real", "'-Infinity'::real", REAL, "CAST(-infinity() AS real)")
                .addRoundTrip("real", "'+Infinity'::real", REAL, "CAST(+infinity() AS real)")
                .execute(getQueryRunner(), postgresCreateAndInsert("postgresql_test_real"));

        SqlDataTypeTest.create()
                .addRoundTrip("real", "NULL", REAL, "CAST(NULL AS real)")
                .addRoundTrip("real", "3.14", REAL, "REAL '3.14'")
                .addRoundTrip("real", "3.1415927", REAL, "REAL '3.1415927'")
                .addRoundTrip("real", "nan()", REAL, "CAST(nan() AS real)")
                .addRoundTrip("real", "-infinity()", REAL, "CAST(-infinity() AS real)")
                .addRoundTrip("real", "+infinity()", REAL, "CAST(+infinity() AS real)")
                .execute(getQueryRunner(), trinoCreateAsSelect("trino__test_real"));
    }

    @Test
    public void testDouble()
    {
        SqlDataTypeTest.create()
                .addRoundTrip("double precision", "NULL", DOUBLE, "CAST(NULL AS double)")
                .addRoundTrip("double precision", "1.0E100", DOUBLE, "1.0E100")
                .addRoundTrip("double precision", "123.456E10", DOUBLE, "123.456E10")
                .addRoundTrip("double precision", "'NaN'::double precision", DOUBLE, "nan()")
                .addRoundTrip("double precision", "'+Infinity'::double precision", DOUBLE, "+infinity()")
                .addRoundTrip("double precision", "'-Infinity'::double precision", DOUBLE, "-infinity()")
                .execute(getQueryRunner(), postgresCreateAndInsert("postgresql_test_double"));

        SqlDataTypeTest.create()
                .addRoundTrip("double", "NULL", DOUBLE, "CAST(NULL AS double)")
                .addRoundTrip("double", "1.0E100", DOUBLE, "1.0E100")
                .addRoundTrip("double", "123.456E10", DOUBLE, "123.456E10")
                .addRoundTrip("double", "nan()", DOUBLE, "nan()")
                .addRoundTrip("double", "+infinity()", DOUBLE, "+infinity()")
                .addRoundTrip("double", "-infinity()", DOUBLE, "-infinity()")
                .execute(getQueryRunner(), trinoCreateAsSelect("trino__test_double"));
    }

    @Test
    public void testDecimal()
    {
        SqlDataTypeTest.create()
                .addRoundTrip("decimal(3, 0)", "CAST('193' AS decimal(3, 0))", createDecimalType(3, 0), "CAST('193' AS decimal(3, 0))")
                .addRoundTrip("decimal(3, 0)", "CAST('19' AS decimal(3, 0))", createDecimalType(3, 0), "CAST('19' AS decimal(3, 0))")
                .addRoundTrip("decimal(3, 0)", "CAST('-193' AS decimal(3, 0))", createDecimalType(3, 0), "CAST('-193' AS decimal(3, 0))")
                .addRoundTrip("decimal(3, 1)", "CAST('10.0' AS decimal(3, 1))", createDecimalType(3, 1), "CAST('10.0' AS decimal(3, 1))")
                .addRoundTrip("decimal(3, 1)", "CAST('10.1' AS decimal(3, 1))", createDecimalType(3, 1), "CAST('10.1' AS decimal(3, 1))")
                .addRoundTrip("decimal(3, 1)", "CAST('-10.1' AS decimal(3, 1))", createDecimalType(3, 1), "CAST('-10.1' AS decimal(3, 1))")
                .addRoundTrip("decimal(4, 2)", "CAST('2' AS decimal(4, 2))", createDecimalType(4, 2), "CAST('2' AS decimal(4, 2))")
                .addRoundTrip("decimal(4, 2)", "CAST('2.3' AS decimal(4, 2))", createDecimalType(4, 2), "CAST('2.3' AS decimal(4, 2))")
                .addRoundTrip("decimal(24, 2)", "CAST('2' AS decimal(24, 2))", createDecimalType(24, 2), "CAST('2' AS decimal(24, 2))")
                .addRoundTrip("decimal(24, 2)", "CAST('2.3' AS decimal(24, 2))", createDecimalType(24, 2), "CAST('2.3' AS decimal(24, 2))")
                .addRoundTrip("decimal(24, 2)", "CAST('123456789.3' AS decimal(24, 2))", createDecimalType(24, 2), "CAST('123456789.3' AS decimal(24, 2))")
                .addRoundTrip("decimal(24, 4)", "CAST('12345678901234567890.31' AS decimal(24, 4))", createDecimalType(24, 4), "CAST('12345678901234567890.31' AS decimal(24, 4))")
                .addRoundTrip("decimal(30, 5)", "CAST('3141592653589793238462643.38327' AS decimal(30, 5))", createDecimalType(30, 5), "CAST('3141592653589793238462643.38327' AS decimal(30, 5))")
                .addRoundTrip("decimal(30, 5)", "CAST('-3141592653589793238462643.38327' AS decimal(30, 5))", createDecimalType(30, 5), "CAST('-3141592653589793238462643.38327' AS decimal(30, 5))")
                .addRoundTrip("decimal(38, 0)", "CAST('27182818284590452353602874713526624977' AS decimal(38, 0))", createDecimalType(38, 0), "CAST('27182818284590452353602874713526624977' AS decimal(38, 0))")
                .addRoundTrip("decimal(38, 0)", "CAST('-27182818284590452353602874713526624977' AS decimal(38, 0))", createDecimalType(38, 0), "CAST('-27182818284590452353602874713526624977' AS decimal(38, 0))")
                .execute(getQueryRunner(), postgresCreateAndInsert("test_decimal"))
                .execute(getQueryRunner(), trinoCreateAsSelect("test_decimal"));

        SqlDataTypeTest.create()
                .addRoundTrip("numeric", "1.1", createDecimalType(Decimals.MAX_PRECISION, 5), "CAST(1.1 AS DECIMAL(38, 5))")
                .execute(getQueryRunner(), sessionWithDecimalMappingAllowOverflow(UNNECESSARY, 5), postgresCreateAndInsert("test_unspecified_decimal"));
    }

    @Test
    public void testChar()
    {
        SqlDataTypeTest.create()
                .addRoundTrip("char(10)", "'text_a'", createCharType(10), "CAST('text_a' AS char(10))")
                .addRoundTrip("char(255)", "'text_b'", createCharType(255), "CAST('text_b' AS char(255))")
                .addRoundTrip("char(5)", "'攻殻機動隊'", createCharType(5), "CAST('攻殻機動隊' AS char(5))")
                .addRoundTrip("char(32)", "'攻殻機動隊'", createCharType(32), "CAST('攻殻機動隊' AS char(32))")
                .addRoundTrip("char(1)", "'😂'", createCharType(1), "CAST('😂' AS char(1))")
                .addRoundTrip("char(77)", "'Ну, погоди!'", createCharType(77), "CAST('Ну, погоди!' AS char(77))")
                .execute(getQueryRunner(), postgresCreateAndInsert("test_char"))
                .execute(getQueryRunner(), trinoCreateAsSelect("test_char"));

        // too long for a char in Trino
        int length = CharType.MAX_LENGTH + 1;
        String postgresqlType = format("char(%s)", length);
        Type trinoType = createVarcharType(length);
        SqlDataTypeTest.create()
                .addRoundTrip(postgresqlType, "'test_f'", trinoType, format("'test_f%s'", " ".repeat(length - 6)))
                .addRoundTrip(postgresqlType, format("'%s'", "a".repeat(length)), trinoType, format("'%s'", "a".repeat(length)))
                .addRoundTrip(postgresqlType, "'\uD83D\uDE02'", trinoType, format("'\uD83D\uDE02%s'", " ".repeat(length - 1)))
                .execute(getQueryRunner(), postgresCreateAndInsert("test_char"));
    }

    @Test
    public void testPostgreSqlCreatedVarchar()
    {
        varcharDataTypeTest(DataType::varcharDataType)
                .execute(getQueryRunner(), postgresCreateAndInsert("test_varchar"));

        varcharDataTypeTest(length -> varcharDataType())
                .execute(getQueryRunner(), postgresCreateAndInsert("test_varchar"));
    }

    @Test
    public void testTrinoCreatedVarchar()
    {
        varcharDataTypeTest(DataType::varcharDataType)
                .execute(getQueryRunner(), trinoCreateAsSelect("test_varchar"));

        varcharDataTypeTest(length -> varcharDataType())
                .execute(getQueryRunner(), trinoCreateAsSelect("test_varchar"));
    }

    private DataTypeTest varcharDataTypeTest(Function<Integer, DataType<String>> dataTypeFactory)
    {
        return characterDataTypeTest(dataTypeFactory)
                .addRoundTrip(dataTypeFactory.apply(10485760), "text_f"); // too long for a char in Trino
    }

    private DataTypeTest characterDataTypeTest(Function<Integer, DataType<String>> dataTypeFactory)
    {
        String sampleUnicodeText = "\u653b\u6bbb\u6a5f\u52d5\u968a";
        String sampleFourByteUnicodeCharacter = "\uD83D\uDE02";

        return DataTypeTest.create()
                .addRoundTrip(dataTypeFactory.apply(10), "text_a")
                .addRoundTrip(dataTypeFactory.apply(255), "text_b")
                .addRoundTrip(dataTypeFactory.apply(65535), "text_d")

                .addRoundTrip(dataTypeFactory.apply(sampleUnicodeText.length()), sampleUnicodeText)
                .addRoundTrip(dataTypeFactory.apply(32), sampleUnicodeText)
                .addRoundTrip(dataTypeFactory.apply(20000), sampleUnicodeText)
                .addRoundTrip(dataTypeFactory.apply(1), sampleFourByteUnicodeCharacter)
                .addRoundTrip(dataTypeFactory.apply(77), "\u041d\u0443, \u043f\u043e\u0433\u043e\u0434\u0438!");
    }

    @Test
    public void testVarbinary()
    {
        // PostgreSQL's BYTEA is mapped to Trino VARBINARY. PostgreSQL does not have VARBINARY type.
        SqlDataTypeTest.create()
                .addRoundTrip("bytea", "NULL", VARBINARY, "CAST(NULL AS varbinary)")
                .addRoundTrip("bytea", "bytea E'\\\\x'", VARBINARY, "X''")
                .addRoundTrip("bytea", utf8ByteaLiteral("hello"), VARBINARY, "to_utf8('hello')")
                .addRoundTrip("bytea", utf8ByteaLiteral("Piękna łąka w 東京都"), VARBINARY, "to_utf8('Piękna łąka w 東京都')")
                .addRoundTrip("bytea", utf8ByteaLiteral("Bag full of 💰"), VARBINARY, "to_utf8('Bag full of 💰')")
                .addRoundTrip("bytea", "bytea E'\\\\x0001020304050607080DF9367AA7000000'", VARBINARY, "X'0001020304050607080DF9367AA7000000'") // non-text
                .addRoundTrip("bytea", "bytea E'\\\\x000000000000'", VARBINARY, "X'000000000000'")
                .execute(getQueryRunner(), postgresCreateAndInsert("test_varbinary"));

        SqlDataTypeTest.create()
                .addRoundTrip("varbinary", "NULL", VARBINARY, "CAST(NULL AS varbinary)")
                .addRoundTrip("varbinary", "X''", VARBINARY, "X''")
                .addRoundTrip("varbinary", "X'68656C6C6F'", VARBINARY, "to_utf8('hello')")
                .addRoundTrip("varbinary", "X'5069C4996B6E6120C582C4856B61207720E69DB1E4BAACE983BD'", VARBINARY, "to_utf8('Piękna łąka w 東京都')")
                .addRoundTrip("varbinary", "X'4261672066756C6C206F6620F09F92B0'", VARBINARY, "to_utf8('Bag full of 💰')")
                .addRoundTrip("varbinary", "X'0001020304050607080DF9367AA7000000'", VARBINARY, "X'0001020304050607080DF9367AA7000000'") // non-text
                .addRoundTrip("varbinary", "X'000000000000'", VARBINARY, "X'000000000000'")
                .execute(getQueryRunner(), trinoCreateAsSelect("test_varbinary"));
    }

    private static String utf8ByteaLiteral(String string)
    {
        return format("bytea E'\\\\x%s'", base16().encode(string.getBytes(UTF_8)));
    }

    @Test
    public void testForcedMappingToVarchar()
    {
        JdbcSqlExecutor jdbcSqlExecutor = new JdbcSqlExecutor(postgreSqlServer.getJdbcUrl(), postgreSqlServer.getProperties());
        jdbcSqlExecutor.execute("CREATE TABLE test_forced_varchar_mapping(tsrange_col tsrange, inet_col inet, tsrange_arr_col tsrange[], unsupported_nonforced_column tstzrange)");
        jdbcSqlExecutor.execute("INSERT INTO test_forced_varchar_mapping(tsrange_col, inet_col, tsrange_arr_col, unsupported_nonforced_column) " +
                "VALUES ('[2010-01-01 14:30, 2010-01-01 15:30)'::tsrange, '172.0.0.1'::inet, array['[2010-01-01 14:30, 2010-01-01 15:30)'::tsrange], '[2010-01-01 14:30, 2010-01-01 15:30)'::tstzrange)");
        try {
            assertQuery(
                    sessionWithArrayAsArray(),
                    "SELECT column_name, data_type FROM information_schema.columns WHERE table_schema = 'tpch' AND table_name = 'test_forced_varchar_mapping'",
                    "VALUES ('tsrange_col','varchar'),('inet_col','varchar'),('tsrange_arr_col','array(varchar)')"); // no 'unsupported_nonforced_column'

            assertQuery(
                    sessionWithArrayAsArray(),
                    "SELECT * FROM test_forced_varchar_mapping",
                    "VALUES ('[\"2010-01-01 14:30:00\",\"2010-01-01 15:30:00\")','172.0.0.1',ARRAY['[\"2010-01-01 14:30:00\",\"2010-01-01 15:30:00\")'])");

            // test predicate pushdown to column that has forced varchar mapping
            assertThat(query("SELECT 1 FROM test_forced_varchar_mapping WHERE tsrange_col = '[\"2010-01-01 14:30:00\",\"2010-01-01 15:30:00\")'"))
                    .matches("VALUES 1")
                    .isNotFullyPushedDown(FilterNode.class);
            assertThat(query("SELECT 1 FROM test_forced_varchar_mapping WHERE tsrange_col = 'some value'"))
                    .returnsEmptyResult()
                    .isNotFullyPushedDown(FilterNode.class);

            // test insert into column that has forced varchar mapping
            assertQueryFails(
                    "INSERT INTO test_forced_varchar_mapping (tsrange_col) VALUES ('some value')",
                    "Underlying type that is mapped to VARCHAR is not supported for INSERT: tsrange");
        }
        finally {
            jdbcSqlExecutor.execute("DROP TABLE test_forced_varchar_mapping");
        }
    }

    @Test
    public void testDecimalExceedingPrecisionMaxIgnored()
    {
        testUnsupportedDataTypeAsIgnored("decimal(50,0)", "12345678901234567890123456789012345678901234567890");
    }

    @Test
    public void testDecimalExceedingPrecisionMaxConvertedToVarchar()
    {
        testUnsupportedDataTypeConvertedToVarchar(
                getSession(),
                "decimal(50,0)",
                "numeric",
                "12345678901234567890123456789012345678901234567890",
                "'12345678901234567890123456789012345678901234567890'");
    }

    @Test
    public void testDecimalExceedingPrecisionMaxWithExceedingIntegerValues()
    {
        JdbcSqlExecutor jdbcSqlExecutor = new JdbcSqlExecutor(postgreSqlServer.getJdbcUrl(), postgreSqlServer.getProperties());

        try (TestTable testTable = new TestTable(
                jdbcSqlExecutor,
                "test_exceeding_max_decimal",
                "(d_col decimal(65,25))",
                asList("1234567890123456789012345678901234567890.123456789", "-1234567890123456789012345678901234567890.123456789"))) {
            assertQuery(
                    sessionWithDecimalMappingAllowOverflow(UNNECESSARY, 0),
                    format("SELECT column_name, data_type FROM information_schema.columns WHERE table_schema = 'tpch' AND table_name = '%s'", testTable.getName()),
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
                    format("SELECT column_name, data_type FROM information_schema.columns WHERE table_schema = 'tpch' AND table_name = '%s'", testTable.getName()),
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
        JdbcSqlExecutor jdbcSqlExecutor = new JdbcSqlExecutor(postgreSqlServer.getJdbcUrl(), postgreSqlServer.getProperties());

        try (TestTable testTable = new TestTable(
                jdbcSqlExecutor,
                "test_exceeding_max_decimal",
                "(d_col decimal(60,20))",
                asList("123456789012345678901234567890.123456789012345", "-123456789012345678901234567890.123456789012345"))) {
            assertQuery(
                    sessionWithDecimalMappingAllowOverflow(UNNECESSARY, 0),
                    format("SELECT column_name, data_type FROM information_schema.columns WHERE table_schema = 'tpch' AND table_name = '%s'", testTable.getName()),
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
                    format("SELECT column_name, data_type FROM information_schema.columns WHERE table_schema = 'tpch' AND table_name = '%s'", testTable.getName()),
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
                    format("SELECT column_name, data_type FROM information_schema.columns WHERE table_schema = 'tpch' AND table_name = '%s'", testTable.getName()),
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
                    format("SELECT column_name, data_type FROM information_schema.columns WHERE table_schema = 'tpch' AND table_name = '%s'", testTable.getName()),
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
        JdbcSqlExecutor jdbcSqlExecutor = new JdbcSqlExecutor(postgreSqlServer.getJdbcUrl(), postgreSqlServer.getProperties());

        try (TestTable testTable = new TestTable(
                jdbcSqlExecutor,
                "test_exceeding_max_decimal",
                format("(d_col decimal(%d,%d))", typePrecision, typeScale),
                asList("12.01", "-12.01", "123", "-123", "1.12345678", "-1.12345678"))) {
            assertQuery(
                    sessionWithDecimalMappingAllowOverflow(UNNECESSARY, 0),
                    format("SELECT column_name, data_type FROM information_schema.columns WHERE table_schema = 'tpch' AND table_name = '%s'", testTable.getName()),
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
                    format("SELECT column_name, data_type FROM information_schema.columns WHERE table_schema = 'tpch' AND table_name = '%s'", testTable.getName()),
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
                    format("SELECT column_name, data_type FROM information_schema.columns WHERE table_schema = 'tpch' AND table_name = '%s'", testTable.getName()),
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

    @Test
    public void testDecimalUnspecifiedPrecisionWithSupportedValues()
    {
        JdbcSqlExecutor jdbcSqlExecutor = new JdbcSqlExecutor(postgreSqlServer.getJdbcUrl(), postgreSqlServer.getProperties());

        try (TestTable testTable = new TestTable(
                jdbcSqlExecutor,
                "test_var_decimal",
                "(d_col decimal)",
                asList("1.12", "123456.789", "-1.12", "-123456.789"))) {
            assertQuery(
                    sessionWithDecimalMappingAllowOverflow(UNNECESSARY, 0),
                    format("SELECT column_name, data_type FROM information_schema.columns WHERE table_schema = 'tpch' AND table_name = '%s'", testTable.getName()),
                    "VALUES ('d_col','decimal(38,0)')");
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
                    format("SELECT column_name, data_type FROM information_schema.columns WHERE table_schema = 'tpch' AND table_name = '%s'", testTable.getName()),
                    "VALUES ('d_col','decimal(38,1)')");
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
                    format("SELECT column_name, data_type FROM information_schema.columns WHERE table_schema = 'tpch' AND table_name = '%s'", testTable.getName()),
                    "VALUES ('d_col','decimal(38,3)')");
            assertQuery(
                    sessionWithDecimalMappingAllowOverflow(UNNECESSARY, 3),
                    "SELECT d_col FROM " + testTable.getName(),
                    "VALUES (1.12), (123456.789), (-1.12), (-123456.789)");
        }
    }

    @Test
    public void testDecimalUnspecifiedPrecisionWithExceedingValue()
    {
        JdbcSqlExecutor jdbcSqlExecutor = new JdbcSqlExecutor(postgreSqlServer.getJdbcUrl(), postgreSqlServer.getProperties());
        try (TestTable testTable = new TestTable(
                jdbcSqlExecutor,
                "test_var_decimal_with_exceeding_value",
                "(key varchar(5), d_col decimal)",
                asList("NULL, '1.12'", "NULL, '1234567890123456789012345678901234567890.1234567'"))) {
            assertQuery(
                    sessionWithDecimalMappingAllowOverflow(UNNECESSARY, 0),
                    format("SELECT column_name, data_type FROM information_schema.columns WHERE table_schema = 'tpch' AND table_name = '%s'", testTable.getName()),
                    "VALUES ('key', 'varchar(5)'),('d_col', 'decimal(38,0)')");
            assertQueryFails(
                    sessionWithDecimalMappingAllowOverflow(UNNECESSARY, 0),
                    "SELECT * FROM " + testTable.getName(),
                    "Rounding necessary");
            assertQueryFails(
                    sessionWithDecimalMappingAllowOverflow(HALF_UP, 0),
                    "SELECT * FROM " + testTable.getName(),
                    "Decimal overflow");
            assertQuery(
                    sessionWithDecimalMappingStrict(CONVERT_TO_VARCHAR),
                    format("SELECT column_name, data_type FROM information_schema.columns WHERE table_schema = 'tpch' AND table_name = '%s'", testTable.getName()),
                    "VALUES ('key', 'varchar(5)'),('d_col', 'varchar')");
            assertQuery(
                    sessionWithDecimalMappingStrict(CONVERT_TO_VARCHAR),
                    "SELECT * FROM " + testTable.getName(),
                    "VALUES (NULL, '1.12'), (NULL, '1234567890123456789012345678901234567890.1234567')");
            assertQuery(
                    sessionWithDecimalMappingStrict(IGNORE),
                    format("SELECT column_name, data_type FROM information_schema.columns WHERE table_schema = 'tpch' AND table_name = '%s'", testTable.getName()),
                    "VALUES ('key', 'varchar(5)')");
        }
    }

    @Test
    public void testArrayDisabled()
    {
        Session session = Session.builder(getSession())
                .setCatalogSessionProperty("postgresql", PostgreSqlSessionProperties.ARRAY_MAPPING, DISABLED.name())
                .build();

        testUnsupportedDataTypeAsIgnored(session, "bigint[]", "ARRAY[42]");
        testUnsupportedDataTypeConvertedToVarchar(session, "bigint[]", "_int8", "ARRAY[42]", "'{42}'");

        testUnsupportedDataTypeAsIgnored(session, "bytea[]", "ARRAY['binary'::bytea]");
        testUnsupportedDataTypeConvertedToVarchar(session, "bytea[]", "_bytea", "ARRAY['binary'::bytea]", "'{\"\\\\x62696e617279\"}'");
    }

    @Test
    public void testArray()
    {
        Session session = sessionWithArrayAsArray();

        // basic types
        DataTypeTest.create(true)
                .addRoundTrip(arrayDataType(booleanDataType()), asList(true, false))
                .addRoundTrip(arrayDataType(bigintDataType()), asList(123_456_789_012L))
                .addRoundTrip(arrayDataType(integerDataType()), asList(1, 2, 1_234_567_890))
                .addRoundTrip(arrayDataType(smallintDataType()), asList((short) 32_456))
                .addRoundTrip(arrayDataType(doubleDataType()), asList(123.45d))
                .addRoundTrip(arrayDataType(realDataType()), asList(123.45f))
                .execute(getQueryRunner(), session, trinoCreateAsSelect(session, "test_array_basic"));

        arrayDateTest(TestPostgreSqlTypeMapping::arrayDataType)
                .execute(getQueryRunner(), session, trinoCreateAsSelect(session, "test_array_date"));
        arrayDateTest(TestPostgreSqlTypeMapping::postgresArrayDataType)
                .execute(getQueryRunner(), session, postgresCreateAndInsert("test_array_date"));

        arrayDecimalTest(TestPostgreSqlTypeMapping::arrayDataType)
                .execute(getQueryRunner(), session, trinoCreateAsSelect(session, "test_array_decimal"));
        arrayDecimalTest(TestPostgreSqlTypeMapping::postgresArrayDataType)
                .execute(getQueryRunner(), session, postgresCreateAndInsert("test_array_decimal"));

        arrayVarcharDataTypeTest(TestPostgreSqlTypeMapping::arrayDataType)
                .execute(getQueryRunner(), session, trinoCreateAsSelect(session, "test_array_varchar"));
        arrayVarcharDataTypeTest(TestPostgreSqlTypeMapping::postgresArrayDataType)
                .execute(getQueryRunner(), session, postgresCreateAndInsert("test_array_varchar"));

        testUnsupportedDataTypeAsIgnored(session, "bytea[]", "ARRAY['binary value'::bytea]");
        testUnsupportedDataTypeAsIgnored(session, "bytea[]", "ARRAY[ARRAY['binary value'::bytea]]");
        testUnsupportedDataTypeAsIgnored(session, "bytea[]", "ARRAY[ARRAY[ARRAY['binary value'::bytea]]]");
        testUnsupportedDataTypeAsIgnored(session, "_bytea", "ARRAY['binary value'::bytea]");
        testUnsupportedDataTypeConvertedToVarchar(session, "bytea[]", "_bytea", "ARRAY['binary value'::bytea]", "'{\"\\\\x62696e6172792076616c7565\"}'");

        arrayUnicodeDataTypeTest(TestPostgreSqlTypeMapping::arrayDataType, DataType::charDataType)
                .execute(getQueryRunner(), session, trinoCreateAsSelect(session, "test_array_parameterized_char_unicode"));
        arrayUnicodeDataTypeTest(TestPostgreSqlTypeMapping::postgresArrayDataType, DataType::charDataType)
                .execute(getQueryRunner(), session, postgresCreateAndInsert("test_array_parameterized_char_unicode"));
        arrayVarcharUnicodeDataTypeTest(TestPostgreSqlTypeMapping::arrayDataType)
                .execute(getQueryRunner(), session, trinoCreateAsSelect(session, "test_array_parameterized_varchar_unicode"));
        arrayVarcharUnicodeDataTypeTest(TestPostgreSqlTypeMapping::postgresArrayDataType)
                .execute(getQueryRunner(), session, postgresCreateAndInsert("test_array_parameterized_varchar_unicode"));
    }

    @Test
    public void testInternalArray()
    {
        DataTypeTest.create()
                .addRoundTrip(arrayDataType(integerDataType(), "_int4"), asList(1, 2, 3))
                .addRoundTrip(arrayDataType(varcharDataType(), "_text"), asList("a", "b"))
                .execute(getQueryRunner(), sessionWithArrayAsArray(), postgresCreateAndInsert("test_array_with_native_name"));
    }

    @Test
    public void testArrayEmptyOrNulls()
    {
        DataTypeTest.create()
                .addRoundTrip(arrayDataType(bigintDataType()), asList())
                .addRoundTrip(arrayDataType(booleanDataType()), null)
                .addRoundTrip(arrayDataType(realDataType()), singletonList(null))
                .addRoundTrip(arrayDataType(integerDataType()), asList(1, null, 3, null))
                .addRoundTrip(arrayDataType(timestampDataType(3)), asList())
                .addRoundTrip(arrayDataType(timestampDataType(3)), singletonList(null))
                .addRoundTrip(arrayDataType(trinoTimestampWithTimeZoneDataType(3)), asList())
                .addRoundTrip(arrayDataType(trinoTimestampWithTimeZoneDataType(3)), singletonList(null))
                .execute(getQueryRunner(), sessionWithArrayAsArray(), trinoCreateAsSelect(sessionWithArrayAsArray(), "test_array_empty_or_nulls"));
    }

    private DataTypeTest arrayDecimalTest(Function<DataType<BigDecimal>, DataType<List<BigDecimal>>> arrayTypeFactory)
    {
        return DataTypeTest.create()
                .addRoundTrip(arrayTypeFactory.apply(decimalDataType(3, 0)), asList(new BigDecimal("193"), new BigDecimal("19"), new BigDecimal("-193")))
                .addRoundTrip(arrayTypeFactory.apply(decimalDataType(3, 1)), asList(new BigDecimal("10.0"), new BigDecimal("10.1"), new BigDecimal("-10.1")))
                .addRoundTrip(arrayTypeFactory.apply(decimalDataType(4, 2)), asList(new BigDecimal("2"), new BigDecimal("2.3")))
                .addRoundTrip(arrayTypeFactory.apply(decimalDataType(24, 2)), asList(new BigDecimal("2"), new BigDecimal("2.3"), new BigDecimal("123456789.3")))
                .addRoundTrip(arrayTypeFactory.apply(decimalDataType(24, 4)), asList(new BigDecimal("12345678901234567890.31")))
                .addRoundTrip(arrayTypeFactory.apply(decimalDataType(30, 5)), asList(new BigDecimal("3141592653589793238462643.38327"), new BigDecimal("-3141592653589793238462643.38327")))
                .addRoundTrip(arrayTypeFactory.apply(decimalDataType(38, 0)), asList(
                        new BigDecimal("27182818284590452353602874713526624977"),
                        new BigDecimal("-27182818284590452353602874713526624977")));
    }

    private DataTypeTest arrayVarcharDataTypeTest(Function<DataType<String>, DataType<List<String>>> arrayTypeFactory)
    {
        return DataTypeTest.create()
                .addRoundTrip(arrayTypeFactory.apply(varcharDataType(10)), asList("text_a"))
                .addRoundTrip(arrayTypeFactory.apply(varcharDataType(255)), asList("text_b"))
                .addRoundTrip(arrayTypeFactory.apply(varcharDataType(65535)), asList("text_d"))
                .addRoundTrip(arrayTypeFactory.apply(varcharDataType(10485760)), asList("text_f"))
                .addRoundTrip(arrayTypeFactory.apply(varcharDataType()), asList("unbounded"));
    }

    private DataTypeTest arrayVarcharUnicodeDataTypeTest(Function<DataType<String>, DataType<List<String>>> arrayTypeFactory)
    {
        return arrayUnicodeDataTypeTest(arrayTypeFactory, DataType::varcharDataType)
                .addRoundTrip(arrayTypeFactory.apply(varcharDataType()), asList("\u041d\u0443, \u043f\u043e\u0433\u043e\u0434\u0438!"));
    }

    private DataTypeTest arrayUnicodeDataTypeTest(Function<DataType<String>, DataType<List<String>>> arrayTypeFactory, Function<Integer, DataType<String>> dataTypeFactory)
    {
        String sampleUnicodeText = "\u653b\u6bbb\u6a5f\u52d5\u968a";
        String sampleFourByteUnicodeCharacter = "\uD83D\uDE02";

        return DataTypeTest.create()
                .addRoundTrip(arrayTypeFactory.apply(dataTypeFactory.apply(sampleUnicodeText.length())), asList(sampleUnicodeText))
                .addRoundTrip(arrayTypeFactory.apply(dataTypeFactory.apply(32)), asList(sampleUnicodeText))
                .addRoundTrip(arrayTypeFactory.apply(dataTypeFactory.apply(20000)), asList(sampleUnicodeText))
                .addRoundTrip(arrayTypeFactory.apply(dataTypeFactory.apply(1)), asList(sampleFourByteUnicodeCharacter));
    }

    private DataTypeTest arrayDateTest(Function<DataType<LocalDate>, DataType<List<LocalDate>>> arrayTypeFactory)
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
                .addRoundTrip(arrayTypeFactory.apply(dateDataType()), asList(LocalDate.of(1952, 4, 3))) // before epoch
                .addRoundTrip(arrayTypeFactory.apply(dateDataType()), asList(LocalDate.of(1970, 1, 1)))
                .addRoundTrip(arrayTypeFactory.apply(dateDataType()), asList(LocalDate.of(1970, 2, 3)))
                .addRoundTrip(arrayTypeFactory.apply(dateDataType()), asList(LocalDate.of(2017, 7, 1))) // summer on northern hemisphere (possible DST)
                .addRoundTrip(arrayTypeFactory.apply(dateDataType()), asList(LocalDate.of(2017, 1, 1))) // winter on northern hemisphere (possible DST on southern hemisphere)
                .addRoundTrip(arrayTypeFactory.apply(dateDataType()), asList(dateOfLocalTimeChangeForwardAtMidnightInJvmZone))
                .addRoundTrip(arrayTypeFactory.apply(dateDataType()), asList(dateOfLocalTimeChangeForwardAtMidnightInSomeZone))
                .addRoundTrip(arrayTypeFactory.apply(dateDataType()), asList(dateOfLocalTimeChangeBackwardAtMidnightInSomeZone));
    }

    @Test
    public void testArrayMultidimensional()
    {
        // for multidimensional arrays, PostgreSQL requires subarrays to have the same dimensions, including nulls
        // e.g. [[1], [1, 2]] and [null, [1, 2]] are not allowed, but [[null, null], [1, 2]] is allowed
        DataTypeTest.create()
                .addRoundTrip(arrayDataType(arrayDataType(booleanDataType())), asList(asList(null, null, null)))
                .addRoundTrip(arrayDataType(arrayDataType(booleanDataType())), asList(asList(true, null), asList(null, null), asList(false, false)))
                .addRoundTrip(arrayDataType(arrayDataType(integerDataType())), asList(asList(1, 2), asList(null, null), asList(3, 4)))
                .addRoundTrip(arrayDataType(arrayDataType(decimalDataType(3, 0))), asList(
                        asList(new BigDecimal("193")),
                        asList(new BigDecimal("19")),
                        asList(new BigDecimal("-193"))))
                .execute(getQueryRunner(), sessionWithArrayAsArray(), trinoCreateAsSelect(sessionWithArrayAsArray(), "test_array_2d"));

        DataTypeTest.create()
                .addRoundTrip(arrayDataType(arrayDataType(arrayDataType(doubleDataType()))), asList(
                        asList(asList(123.45d), asList(678.99d)),
                        asList(asList(543.21d), asList(998.76d)),
                        asList(asList(567.123d), asList(789.12d))))
                .addRoundTrip(arrayDataType(arrayDataType(arrayDataType(dateDataType()))), asList(
                        asList(asList(LocalDate.of(1952, 4, 3), LocalDate.of(1970, 1, 1))),
                        asList(asList(null, LocalDate.of(1970, 1, 1))),
                        asList(asList(LocalDate.of(1970, 2, 3), LocalDate.of(2017, 7, 1)))))
                .execute(getQueryRunner(), sessionWithArrayAsArray(), trinoCreateAsSelect(sessionWithArrayAsArray(), "test_array_3d"));
    }

    @Test
    public void testArrayAsJson()
    {
        Session session = Session.builder(getSession())
                .setSystemProperty("postgresql.array_mapping", AS_JSON.name())
                .build();

        DataTypeTest.create()
                .addRoundTrip(arrayAsJsonDataType("boolean[]"), null)
                .addRoundTrip(arrayAsJsonDataType("boolean[]"), "[[true,false],[false,true],[true,true]]")
                .addRoundTrip(arrayAsJsonDataType("boolean[3][2]"), "[[true,false],[false,true],[true,true]]")
                .addRoundTrip(arrayAsJsonDataType("boolean[100][100][100]"), "[true]")
                .addRoundTrip(arrayAsJsonDataType("_bool"), "[[true,false],[null,null]]")
                .addRoundTrip(arrayAsJsonDataType("_bool"), "[[[null]]]")
                .addRoundTrip(arrayAsJsonDataType("_bool"), "[]")
                .execute(getQueryRunner(), session, postgresCreateAndInsert("test_boolean_array_as_json"));

        DataTypeTest.create()
                .addRoundTrip(arrayAsJsonDataType("integer[]"), null)
                .addRoundTrip(arrayAsJsonDataType("integer[]"), "[[[1,2,3],[4,5,6]],[[7,8,9],[10,11,12]]]")
                .addRoundTrip(arrayAsJsonDataType("integer[100][100][100]"), "[0]")
                .addRoundTrip(arrayAsJsonDataType("integer[]"), "[[[null,null]]]")
                .addRoundTrip(arrayAsJsonDataType("integer[]"), "[]")
                .addRoundTrip(arrayAsJsonDataType("_int4"), "[]")
                .addRoundTrip(arrayAsJsonDataType("_int4"), "[[0],[1],[2],[3]]")
                .execute(getQueryRunner(), session, postgresCreateAndInsert("test_integer_array_as_json"));

        DataTypeTest.create()
                .addRoundTrip(arrayAsJsonDataType("double precision[]"), null)
                .addRoundTrip(arrayAsJsonDataType("double precision[]"), "[[[1.1,2.2,3.3],[4.4,5.5,6.6]]]")
                .addRoundTrip(arrayAsJsonDataType("double precision[100][100][100]"), "[42.3]")
                .addRoundTrip(arrayAsJsonDataType("double precision[]"), "[[[null,null]]]")
                .addRoundTrip(arrayAsJsonDataType("double precision[]"), "[]")
                .addRoundTrip(arrayAsJsonDataType("_float8"), "[]")
                .addRoundTrip(arrayAsJsonDataType("_float8"), "[[1.1],[2.2]]")
                .execute(getQueryRunner(), session, postgresCreateAndInsert("test_double_array_as_json"));

        DataTypeTest.create()
                .addRoundTrip(arrayAsJsonDataType("real[]"), null)
                .addRoundTrip(arrayAsJsonDataType("real[]"), "[[[1.1,2.2,3.3],[4.4,5.5,6.6]]]")
                .addRoundTrip(arrayAsJsonDataType("real[100][100][100]"), "[42.3]")
                .addRoundTrip(arrayAsJsonDataType("real[]"), "[[[null,null]]]")
                .addRoundTrip(arrayAsJsonDataType("real[]"), "[]")
                .addRoundTrip(arrayAsJsonDataType("_float4"), "[]")
                .addRoundTrip(arrayAsJsonDataType("_float4"), "[[1.1],[2.2]]")
                .execute(getQueryRunner(), session, postgresCreateAndInsert("test_real_array_as_json"));

        DataTypeTest.create()
                .addRoundTrip(arrayAsJsonDataType("varchar[]"), null)
                .addRoundTrip(arrayAsJsonDataType("varchar[]"), "[\"text\"]")
                .addRoundTrip(arrayAsJsonDataType("_text"), "[[\"one\",\"two\"],[\"three\",\"four\"]]")
                .addRoundTrip(arrayAsJsonDataType("_text"), "[[\"one\",null]]")
                .addRoundTrip(arrayAsJsonDataType("_text"), "[]")
                .execute(getQueryRunner(), session, postgresCreateAndInsert("test_varchar_array_as_json"));

        testUnsupportedDataTypeAsIgnored(session, "bytea[]", "ARRAY['binary value'::bytea]");
        testUnsupportedDataTypeAsIgnored(session, "bytea[]", "ARRAY[ARRAY['binary value'::bytea]]");
        testUnsupportedDataTypeAsIgnored(session, "bytea[]", "ARRAY[ARRAY[ARRAY['binary value'::bytea]]]");
        testUnsupportedDataTypeAsIgnored(session, "_bytea", "ARRAY['binary value'::bytea]");
        testUnsupportedDataTypeConvertedToVarchar(session, "bytea[]", "_bytea", "ARRAY['binary value'::bytea]", " '{\"\\\\x62696e6172792076616c7565\"}' ");

        DataTypeTest.create()
                .addRoundTrip(arrayAsJsonDataType("date[]"), null)
                .addRoundTrip(arrayAsJsonDataType("date[]"), "[\"2019-01-02\"]")
                .addRoundTrip(arrayAsJsonDataType("date[]"), "[null,null]")
                .execute(getQueryRunner(), session, postgresCreateAndInsert("test_timestamp_array_as_json"));

        DataTypeTest.create()
                .addRoundTrip(arrayAsJsonDataType("timestamp[]"), null)
                .addRoundTrip(arrayAsJsonDataType("timestamp[]"), "[\"2019-01-02 03:04:05.789000\"]")
                .addRoundTrip(arrayAsJsonDataType("timestamp[]"), "[null,null]")
                .execute(getQueryRunner(), session, postgresCreateAndInsert("test_timestamp_array_as_json"));

        DataTypeTest.create()
                .addRoundTrip(arrayAsJsonDataType("hstore[]"), null)
                .addRoundTrip(arrayAsJsonDataType("hstore[]"), "[]")
                .addRoundTrip(arrayAsJsonDataType("hstore[]"), "[null,null]")
                .addRoundTrip(hstoreArrayAsJsonDataType(), "[{\"a\":\"1\",\"b\":\"2\"},{\"a\":\"3\",\"d\":\"4\"}]")
                .addRoundTrip(hstoreArrayAsJsonDataType(), "[{\"a\":null,\"b\":\"2\"}]")
                .execute(getQueryRunner(), session, postgresCreateAndInsert("test_hstore_array_as_json"));
    }

    private static <E> DataType<List<E>> arrayDataType(DataType<E> elementType)
    {
        return arrayDataType(elementType, format("ARRAY(%s)", elementType.getInsertType()));
    }

    private static <E> DataType<List<E>> postgresArrayDataType(DataType<E> elementType)
    {
        return arrayDataType(elementType, elementType.getInsertType() + "[]");
    }

    private static <E> DataType<List<E>> arrayDataType(DataType<E> elementType, String insertType)
    {
        return dataType(
                insertType,
                new ArrayType(elementType.getTrinoResultType()),
                valuesList -> "ARRAY" + valuesList.stream().map(elementType::toLiteral).collect(toList()),
                valuesList -> "ARRAY" + valuesList.stream().map(elementType::toTrinoLiteral).collect(toList()),
                valuesList -> valuesList == null ? null : valuesList.stream().map(elementType::toTrinoQueryResult).collect(toList()));
    }

    private static DataType<String> arrayAsJsonDataType(String insertType)
    {
        return dataType(
                insertType,
                JSON,
                // naive conversion JSON array -> array literal, sufficient for tests
                value -> value
                        .replace("[", "ARRAY[")
                        .replace("\"", "'")
                        + "::" + insertType);
    }

    private static DataType<String> hstoreArrayAsJsonDataType()
    {
        return dataType(
                "hstore[]",
                JSON,
                json -> HSTORE_CODEC.fromJson(json).stream()
                        .map(TestPostgreSqlTypeMapping::hstoreLiteral)
                        .collect(joining(",", "ARRAY[", "]")));
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

        DataTypeTest testCases = DataTypeTest.create(true)
                .addRoundTrip(dateDataType(), LocalDate.of(1, 1, 1))
                .addRoundTrip(dateDataType(), LocalDate.of(1582, 10, 4)) // before julian->gregorian switch
                .addRoundTrip(dateDataType(), LocalDate.of(1582, 10, 5)) // begin julian->gregorian switch
                .addRoundTrip(dateDataType(), LocalDate.of(1582, 10, 14)) // end julian->gregorian switch
                .addRoundTrip(dateDataType(), LocalDate.of(1952, 4, 3)) // before epoch
                .addRoundTrip(dateDataType(), LocalDate.of(1970, 1, 1))
                .addRoundTrip(dateDataType(), LocalDate.of(1970, 2, 3))
                .addRoundTrip(dateDataType(), LocalDate.of(2017, 7, 1)) // summer on northern hemisphere (possible DST)
                .addRoundTrip(dateDataType(), LocalDate.of(2017, 1, 1)) // winter on northern hemisphere (possible DST on southern hemisphere)
                .addRoundTrip(dateDataType(), dateOfLocalTimeChangeForwardAtMidnightInJvmZone)
                .addRoundTrip(dateDataType(), dateOfLocalTimeChangeForwardAtMidnightInSomeZone)
                .addRoundTrip(dateDataType(), dateOfLocalTimeChangeBackwardAtMidnightInSomeZone);

        for (String timeZoneId : ImmutableList.of(UTC_KEY.getId(), jvmZone.getId(), someZone.getId())) {
            Session session = Session.builder(getSession())
                    .setTimeZoneKey(TimeZoneKey.getTimeZoneKey(timeZoneId))
                    .build();
            testCases.execute(getQueryRunner(), session, postgresCreateAndInsert("test_date"));
            testCases.execute(getQueryRunner(), session, trinoCreateAsSelect(session, "test_date"));
            testCases.execute(getQueryRunner(), session, trinoCreateAsSelect(getSession(), "test_date"));
            testCases.execute(getQueryRunner(), session, trinoCreateAndInsert(session, "test_date"));
        }
    }

    @Test
    public void testDateMinMax()
    {
        // Merge into 'testDate()' when converting the method to SqlDataTypeTest. Currently, we can't test these values with DataTypeTest.
        SqlDataTypeTest.create()
                .addRoundTrip("DATE", "'4713-01-01 BC'", DATE, "DATE '-4712-01-01'")
                .addRoundTrip("DATE", "'5874897-12-31'", DATE, "DATE '5874897-12-31'")
                .execute(getQueryRunner(), postgresCreateAndInsert("test_date_min_max"));
    }

    @Test
    public void testEnum()
    {
        JdbcSqlExecutor jdbcSqlExecutor = new JdbcSqlExecutor(postgreSqlServer.getJdbcUrl(), postgreSqlServer.getProperties());
        jdbcSqlExecutor.execute("CREATE TYPE enum_t AS ENUM ('a','b','c')");
        jdbcSqlExecutor.execute("CREATE TABLE test_enum(id int, enum_column enum_t)");
        jdbcSqlExecutor.execute("INSERT INTO test_enum(id,enum_column) values (1,'a'::enum_t),(2,'b'::enum_t)");
        try {
            assertQuery(
                    "SELECT column_name, data_type FROM information_schema.columns WHERE table_schema = 'tpch' AND table_name = 'test_enum'",
                    "VALUES ('id','integer'),('enum_column','varchar')");
            assertQuery("SELECT * FROM test_enum", "VALUES (1,'a'),(2,'b')");
            assertQuery("SELECT * FROM test_enum WHERE enum_column='a'", "VALUES (1,'a')");
        }
        finally {
            jdbcSqlExecutor.execute("DROP TABLE test_enum");
            jdbcSqlExecutor.execute("DROP TYPE enum_t");
        }
    }

    /**
     * @see #testTimeCoercion
     */
    @Test(dataProvider = "testTimestampDataProvider")
    public void testTime(boolean insertWithTrino, ZoneId sessionZone)
    {
        LocalTime timeGapInJvmZone = LocalTime.of(0, 12, 34, 567_000_000);
        checkIsGap(jvmZone, timeGapInJvmZone.atDate(EPOCH_DAY));

        DataTypeTest tests = DataTypeTest.create()
                .addRoundTrip(timeDataType(0), LocalTime.of(1, 12, 34, 0))
                .addRoundTrip(timeDataType(1), LocalTime.of(2, 12, 34, 100_000_000))
                .addRoundTrip(timeDataType(2), LocalTime.of(2, 12, 34, 10_000_000))
                .addRoundTrip(timeDataType(3), LocalTime.of(2, 12, 34, 1_000_000))
                .addRoundTrip(timeDataType(3), LocalTime.of(3, 12, 34, 0))
                .addRoundTrip(timeDataType(4), LocalTime.of(4, 12, 34, 0))
                .addRoundTrip(timeDataType(5), LocalTime.of(5, 12, 34, 0))
                .addRoundTrip(timeDataType(5), LocalTime.of(6, 12, 34, 0))
                .addRoundTrip(timeDataType(6), LocalTime.of(9, 12, 34, 0))
                .addRoundTrip(timeDataType(6), LocalTime.of(10, 12, 34, 0))
                .addRoundTrip(timeDataType(6), LocalTime.of(15, 12, 34, 567_000_000))
                .addRoundTrip(timeDataType(6), LocalTime.of(23, 59, 59, 0))
                .addRoundTrip(timeDataType(6), LocalTime.of(23, 59, 59, 999_000_000))
                .addRoundTrip(timeDataType(6), LocalTime.of(23, 59, 59, 999_900_000))
                .addRoundTrip(timeDataType(6), LocalTime.of(23, 59, 59, 999_990_000))
                .addRoundTrip(timeDataType(6), LocalTime.of(23, 59, 59, 999_999_000))
                // epoch is also a gap in JVM zone
                .addRoundTrip(timeDataType(3), epoch.toLocalTime())
                .addRoundTrip(timeDataType(3), timeGapInJvmZone);

        Session session = Session.builder(getSession())
                .setTimeZoneKey(TimeZoneKey.getTimeZoneKey(sessionZone.getId()))
                .build();

        if (insertWithTrino) {
            tests.execute(getQueryRunner(), session, trinoCreateAsSelect(session, "test_time"));
            tests.execute(getQueryRunner(), session, trinoCreateAsSelect(getSession(), "test_time"));
            tests.execute(getQueryRunner(), session, trinoCreateAndInsert(session, "test_time"));
        }
        else {
            tests.execute(getQueryRunner(), session, postgresCreateAndInsert("test_time"));
        }
    }

    /**
     * Additional test supplementing {@link #testTime} with values that do not necessarily round-trip, including
     * timestamp precision higher than expressible with {@code testTime}.
     *
     * @see #testTime
     */
    @Test
    public void testTimeCoercion()
    {
        SqlDataTypeTest.create()

                .addRoundTrip("TIME '00:00:00'", "TIME '00:00:00'")
                .addRoundTrip("TIME '00:00:00.000000'", "TIME '00:00:00.000000'")
                .addRoundTrip("TIME '00:00:00.123456'", "TIME '00:00:00.123456'")
                .addRoundTrip("TIME '12:34:56'", "TIME '12:34:56'")
                .addRoundTrip("TIME '12:34:56.123456'", "TIME '12:34:56.123456'")

                // Cases which require using PGobject instead of e.g. LocalTime with PostgreSQL JDBC driver
                .addRoundTrip("TIME '23:59:59.000000'", "TIME '23:59:59.000000'")
                .addRoundTrip("TIME '23:59:59.900000'", "TIME '23:59:59.900000'")
                .addRoundTrip("TIME '23:59:59.990000'", "TIME '23:59:59.990000'")
                .addRoundTrip("TIME '23:59:59.999000'", "TIME '23:59:59.999000'")
                .addRoundTrip("TIME '23:59:59.999900'", "TIME '23:59:59.999900'")
                .addRoundTrip("TIME '23:59:59.999990'", "TIME '23:59:59.999990'")
                .addRoundTrip("TIME '23:59:59.999999'", "TIME '23:59:59.999999'")

                .addRoundTrip("TIME '23:59:59'", "TIME '23:59:59'")
                .addRoundTrip("TIME '23:59:59.9'", "TIME '23:59:59.9'")
                .addRoundTrip("TIME '23:59:59.99'", "TIME '23:59:59.99'")
                .addRoundTrip("TIME '23:59:59.999'", "TIME '23:59:59.999'")
                .addRoundTrip("TIME '23:59:59.9999'", "TIME '23:59:59.9999'")
                .addRoundTrip("TIME '23:59:59.99999'", "TIME '23:59:59.99999'")
                .addRoundTrip("TIME '23:59:59.999999'", "TIME '23:59:59.999999'")

                // round down
                .addRoundTrip("TIME '00:00:00.0000001'", "TIME '00:00:00.000000'")
                .addRoundTrip("TIME '00:00:00.000000000001'", "TIME '00:00:00.000000'")
                .addRoundTrip("TIME '12:34:56.1234561'", "TIME '12:34:56.123456'")
                .addRoundTrip("TIME '23:59:59.9999994'", "TIME '23:59:59.999999'")
                .addRoundTrip("TIME '23:59:59.999999499999'", "TIME '23:59:59.999999'")

                // round down, maximal value
                .addRoundTrip("TIME '00:00:00.0000004'", "TIME '00:00:00.000000'")
                .addRoundTrip("TIME '00:00:00.00000049'", "TIME '00:00:00.000000'")
                .addRoundTrip("TIME '00:00:00.000000449'", "TIME '00:00:00.000000'")
                .addRoundTrip("TIME '00:00:00.0000004449'", "TIME '00:00:00.000000'")
                .addRoundTrip("TIME '00:00:00.00000044449'", "TIME '00:00:00.000000'")
                .addRoundTrip("TIME '00:00:00.000000444449'", "TIME '00:00:00.000000'")

                // round up, minimal value
                .addRoundTrip("TIME '00:00:00.0000005'", "TIME '00:00:00.000001'")
                .addRoundTrip("TIME '00:00:00.00000050'", "TIME '00:00:00.000001'")
                .addRoundTrip("TIME '00:00:00.000000500'", "TIME '00:00:00.000001'")
                .addRoundTrip("TIME '00:00:00.0000005000'", "TIME '00:00:00.000001'")
                .addRoundTrip("TIME '00:00:00.00000050000'", "TIME '00:00:00.000001'")
                .addRoundTrip("TIME '00:00:00.000000500000'", "TIME '00:00:00.000001'")

                // round up, maximal value
                .addRoundTrip("TIME '00:00:00.0000009'", "TIME '00:00:00.000001'")
                .addRoundTrip("TIME '00:00:00.00000099'", "TIME '00:00:00.000001'")
                .addRoundTrip("TIME '00:00:00.000000999'", "TIME '00:00:00.000001'")
                .addRoundTrip("TIME '00:00:00.0000009999'", "TIME '00:00:00.000001'")
                .addRoundTrip("TIME '00:00:00.00000099999'", "TIME '00:00:00.000001'")
                .addRoundTrip("TIME '00:00:00.000000999999'", "TIME '00:00:00.000001'")

                // round up to next day, minimal value
                .addRoundTrip("TIME '23:59:59.9999995'", "TIME '00:00:00.000000'")
                .addRoundTrip("TIME '23:59:59.99999950'", "TIME '00:00:00.000000'")
                .addRoundTrip("TIME '23:59:59.999999500'", "TIME '00:00:00.000000'")
                .addRoundTrip("TIME '23:59:59.9999995000'", "TIME '00:00:00.000000'")
                .addRoundTrip("TIME '23:59:59.99999950000'", "TIME '00:00:00.000000'")
                .addRoundTrip("TIME '23:59:59.999999500000'", "TIME '00:00:00.000000'")

                // round up to next day, maximal value
                .addRoundTrip("TIME '23:59:59.9999999'", "TIME '00:00:00.000000'")
                .addRoundTrip("TIME '23:59:59.99999999'", "TIME '00:00:00.000000'")
                .addRoundTrip("TIME '23:59:59.999999999'", "TIME '00:00:00.000000'")
                .addRoundTrip("TIME '23:59:59.9999999999'", "TIME '00:00:00.000000'")
                .addRoundTrip("TIME '23:59:59.99999999999'", "TIME '00:00:00.000000'")
                .addRoundTrip("TIME '23:59:59.999999999999'", "TIME '00:00:00.000000'")

                // CTAS with Trino, where the coercion is done by the connector
                .execute(getQueryRunner(), trinoCreateAsSelect(getSession(), "test_time_coercion"))
                // INSERT with Trino, where the coercion is done by the engine
                .execute(getQueryRunner(), trinoCreateAndInsert(getSession(), "test_time_coercion"));
    }

    @Test
    public void testTime24()
    {
        try (TestTable testTable = new TestTable(
                new JdbcSqlExecutor(postgreSqlServer.getJdbcUrl(), postgreSqlServer.getProperties()),
                "test_time_24",
                "(a time(0), b time(3), c time(6))",
                List.of(
                        // "zero" row
                        "TIME '00:00:00', TIME '00:00:00.000', TIME '00:00:00.000000'",
                        // "max" row
                        "TIME '23:59:59', TIME '23:59:59.999', TIME '23:59:59.999999'",
                        // "24" row
                        "TIME '24:00:00', TIME '24:00:00.000', TIME '24:00:00.000000'"))) {
            // select
            assertThat(query("SELECT a, b, c FROM " + testTable.getName()))
                    .matches("VALUES " +
                            "(TIME '00:00:00', TIME '00:00:00.000', TIME '00:00:00.000000'), " +
                            "(TIME '23:59:59', TIME '23:59:59.999', TIME '23:59:59.999999'), " +
                            "(TIME '23:59:59', TIME '23:59:59.999', TIME '23:59:59.999999')");

            // select with predicate -- should not be pushed down
            assertThat(query("SELECT count(*) FROM " + testTable.getName() + " WHERE a = TIME '23:59:59'"))
                    .matches("VALUES BIGINT '2'")
                    .isNotFullyPushedDown(FilterNode.class);
            assertThat(query("SELECT count(*) FROM " + testTable.getName() + " WHERE b = TIME '23:59:59.999'"))
                    .matches("VALUES BIGINT '2'")
                    .isNotFullyPushedDown(FilterNode.class);
            assertThat(query("SELECT count(*) FROM " + testTable.getName() + " WHERE c = TIME '23:59:59.999999'"))
                    .matches("VALUES BIGINT '2'")
                    .isNotFullyPushedDown(FilterNode.class);

            // aggregation should not be pushed down, as it would incorrectly treat values
            // TODO https://github.com/trinodb/trino/issues/5339
//            assertThat(query("SELECT count(*) FROM " + testTable.getName() + " GROUP BY a"))
//                    .matches("VALUES BIGINT '1', BIGINT '2'")
//                    .isNotFullyPushedDown(AggregationNode.class);
//            assertThat(query("SELECT count(*) FROM " + testTable.getName() + " GROUP BY b"))
//                    .matches("VALUES BIGINT '1', BIGINT '2'")
//                    .isNotFullyPushedDown(AggregationNode.class);
//            assertThat(query("SELECT count(*) FROM " + testTable.getName() + " GROUP BY c"))
//                    .matches("VALUES BIGINT '1', BIGINT '2'")
//                    .isNotFullyPushedDown(AggregationNode.class);
        }
    }

    /**
     * @see #testTimestampCoercion
     */
    @Test(dataProvider = "testTimestampDataProvider")
    public void testTimestamp(boolean insertWithTrino, ZoneId sessionZone)
    {
        DataTypeTest tests = DataTypeTest.create(true);

        // no need to test gap for multiple precisions as both Trino and PostgreSql JDBC
        // uses same representation for all precisions 1-6
        DataType<LocalDateTime> timestampDataType = timestampDataType(3);
        tests.addRoundTrip(timestampDataType, beforeEpoch);
        tests.addRoundTrip(timestampDataType, afterEpoch);
        tests.addRoundTrip(timestampDataType, timeDoubledInJvmZone);
        tests.addRoundTrip(timestampDataType, timeDoubledInVilnius);
        tests.addRoundTrip(timestampDataType, epoch); // epoch also is a gap in JVM zone
        tests.addRoundTrip(timestampDataType, timeGapInJvmZone1);
        tests.addRoundTrip(timestampDataType, timeGapInJvmZone2);
        tests.addRoundTrip(timestampDataType, timeGapInVilnius);
        tests.addRoundTrip(timestampDataType, timeGapInKathmandu);

        // test arbitrary time for all supported precisions
        tests.addRoundTrip(timestampDataType(0), LocalDateTime.of(1970, 1, 1, 0, 0, 0));
        tests.addRoundTrip(timestampDataType(1), LocalDateTime.of(1970, 1, 1, 0, 0, 0, 100_000_000));
        tests.addRoundTrip(timestampDataType(2), LocalDateTime.of(1970, 1, 1, 0, 0, 0, 120_000_000));
        tests.addRoundTrip(timestampDataType(3), LocalDateTime.of(1970, 1, 1, 0, 0, 0, 123_000_000));
        tests.addRoundTrip(timestampDataType(4), LocalDateTime.of(1970, 1, 1, 0, 0, 0, 123_400_000));
        tests.addRoundTrip(timestampDataType(5), LocalDateTime.of(1970, 1, 1, 0, 0, 0, 123_450_000));
        tests.addRoundTrip(timestampDataType(6), LocalDateTime.of(1970, 1, 1, 0, 0, 0, 123_456_000));

        // before epoch with second fraction
        tests.addRoundTrip(timestampDataType(6), LocalDateTime.of(1969, 12, 31, 23, 59, 59, 123_000_000));
        tests.addRoundTrip(timestampDataType(6), LocalDateTime.of(1969, 12, 31, 23, 59, 59, 123_456_000));

        Session session = Session.builder(getSession())
                .setTimeZoneKey(TimeZoneKey.getTimeZoneKey(sessionZone.getId()))
                .build();

        if (insertWithTrino) {
            tests.execute(getQueryRunner(), session, trinoCreateAsSelect(session, "test_timestamp"));
            tests.execute(getQueryRunner(), session, trinoCreateAsSelect(getSession(), "test_timestamp"));
            tests.execute(getQueryRunner(), session, trinoCreateAndInsert(session, "test_timestamp"));
        }
        else {
            tests.execute(getQueryRunner(), session, postgresCreateAndInsert("test_timestamp"));
        }
    }

    /**
     * Additional test supplementing {@link #testTimestamp} with values that do not necessarily round-trip, including
     * timestamp precision higher than expressible with {@code LocalDateTime}.
     *
     * @see #testTimestamp
     */
    @Test
    public void testTimestampCoercion()
    {
        SqlDataTypeTest.create()

                // precision 0 ends up as precision 0
                .addRoundTrip("TIMESTAMP '1970-01-01 00:00:00'", "TIMESTAMP '1970-01-01 00:00:00'")

                .addRoundTrip("TIMESTAMP '1970-01-01 00:00:00.1'", "TIMESTAMP '1970-01-01 00:00:00.1'")
                .addRoundTrip("TIMESTAMP '1970-01-01 00:00:00.9'", "TIMESTAMP '1970-01-01 00:00:00.9'")
                .addRoundTrip("TIMESTAMP '1970-01-01 00:00:00.123'", "TIMESTAMP '1970-01-01 00:00:00.123'")
                .addRoundTrip("TIMESTAMP '1970-01-01 00:00:00.123000'", "TIMESTAMP '1970-01-01 00:00:00.123000'")
                .addRoundTrip("TIMESTAMP '1970-01-01 00:00:00.999'", "TIMESTAMP '1970-01-01 00:00:00.999'")
                // max supported precision
                .addRoundTrip("TIMESTAMP '1970-01-01 00:00:00.123456'", "TIMESTAMP '1970-01-01 00:00:00.123456'")

                .addRoundTrip("TIMESTAMP '2020-09-27 12:34:56.1'", "TIMESTAMP '2020-09-27 12:34:56.1'")
                .addRoundTrip("TIMESTAMP '2020-09-27 12:34:56.9'", "TIMESTAMP '2020-09-27 12:34:56.9'")
                .addRoundTrip("TIMESTAMP '2020-09-27 12:34:56.123'", "TIMESTAMP '2020-09-27 12:34:56.123'")
                .addRoundTrip("TIMESTAMP '2020-09-27 12:34:56.123000'", "TIMESTAMP '2020-09-27 12:34:56.123000'")
                .addRoundTrip("TIMESTAMP '2020-09-27 12:34:56.999'", "TIMESTAMP '2020-09-27 12:34:56.999'")
                // max supported precision
                .addRoundTrip("TIMESTAMP '2020-09-27 12:34:56.123456'", "TIMESTAMP '2020-09-27 12:34:56.123456'")

                // round down
                .addRoundTrip("TIMESTAMP '1970-01-01 00:00:00.1234561'", "TIMESTAMP '1970-01-01 00:00:00.123456'")

                // nanoc round up, end result rounds down
                .addRoundTrip("TIMESTAMP '1970-01-01 00:00:00.123456499'", "TIMESTAMP '1970-01-01 00:00:00.123456'")
                .addRoundTrip("TIMESTAMP '1970-01-01 00:00:00.123456499999'", "TIMESTAMP '1970-01-01 00:00:00.123456'")

                // round up
                .addRoundTrip("TIMESTAMP '1970-01-01 00:00:00.1234565'", "TIMESTAMP '1970-01-01 00:00:00.123457'")

                // max precision
                .addRoundTrip("TIMESTAMP '1970-01-01 00:00:00.111222333444'", "TIMESTAMP '1970-01-01 00:00:00.111222'")

                // round up to next second
                .addRoundTrip("TIMESTAMP '1970-01-01 00:00:00.9999995'", "TIMESTAMP '1970-01-01 00:00:01.000000'")

                // round up to next day
                .addRoundTrip("TIMESTAMP '1970-01-01 23:59:59.9999995'", "TIMESTAMP '1970-01-02 00:00:00.000000'")

                // negative epoch
                .addRoundTrip("TIMESTAMP '1969-12-31 23:59:59.9999995'", "TIMESTAMP '1970-01-01 00:00:00.000000'")
                .addRoundTrip("TIMESTAMP '1969-12-31 23:59:59.999999499999'", "TIMESTAMP '1969-12-31 23:59:59.999999'")
                .addRoundTrip("TIMESTAMP '1969-12-31 23:59:59.9999994'", "TIMESTAMP '1969-12-31 23:59:59.999999'")

                // CTAS with Trino, where the coercion is done by the connector
                .execute(getQueryRunner(), trinoCreateAsSelect(getSession(), "test_timestamp_coercion"))
                // INSERT with Trino, where the coercion is done by the engine
                .execute(getQueryRunner(), trinoCreateAndInsert(getSession(), "test_timestamp_coercion"));
    }

    @Test(dataProvider = "testTimestampDataProvider")
    public void testArrayTimestamp(boolean insertWithTrino, ZoneId sessionZone)
    {
        DataTypeTest tests = DataTypeTest.create(true);
        // no need to test gap for multiple precisions as both Trino and PostgreSql JDBC
        // uses same representation for all precisions 1-6
        DataType<List<LocalDateTime>> dataType = arrayOfTimestampDataType(3, insertWithTrino);
        tests.addRoundTrip(dataType, asList(beforeEpoch));
        tests.addRoundTrip(dataType, asList(afterEpoch));
        tests.addRoundTrip(dataType, asList(timeDoubledInJvmZone));
        tests.addRoundTrip(dataType, asList(timeDoubledInVilnius));
        tests.addRoundTrip(dataType, asList(epoch));
        tests.addRoundTrip(dataType, asList(timeGapInJvmZone1));
        tests.addRoundTrip(dataType, asList(timeGapInJvmZone2));
        tests.addRoundTrip(dataType, asList(timeGapInVilnius));
        tests.addRoundTrip(dataType, asList(timeGapInKathmandu));

        // test arbitrary time for all supported precisions
        tests.addRoundTrip(arrayOfTimestampDataType(1, insertWithTrino), asList(LocalDateTime.of(1970, 1, 1, 1, 1, 1, 100_000_000)));
        tests.addRoundTrip(arrayOfTimestampDataType(2, insertWithTrino), asList(LocalDateTime.of(1970, 1, 1, 1, 1, 1, 120_000_000)));
        tests.addRoundTrip(arrayOfTimestampDataType(3, insertWithTrino), asList(LocalDateTime.of(1970, 1, 1, 1, 1, 1, 123_000_000)));
        tests.addRoundTrip(arrayOfTimestampDataType(4, insertWithTrino), asList(LocalDateTime.of(1970, 1, 1, 1, 1, 1, 123_400_000)));
        tests.addRoundTrip(arrayOfTimestampDataType(5, insertWithTrino), asList(LocalDateTime.of(1970, 1, 1, 1, 1, 1, 123_450_000)));
        tests.addRoundTrip(arrayOfTimestampDataType(6, insertWithTrino), asList(LocalDateTime.of(1970, 1, 1, 1, 1, 1, 123_456_000)));

        Session session = Session.builder(sessionWithArrayAsArray())
                .setTimeZoneKey(TimeZoneKey.getTimeZoneKey(sessionZone.getId()))
                .build();

        if (insertWithTrino) {
            tests.execute(getQueryRunner(), session, trinoCreateAsSelect(sessionWithArrayAsArray(), "test_array_timestamp"));
        }
        else {
            tests.execute(getQueryRunner(), session, postgresCreateAndInsert("test_array_timestamp"));
        }
    }

    private DataType<List<LocalDateTime>> arrayOfTimestampDataType(int precision, boolean insertWithTrino)
    {
        if (insertWithTrino) {
            return arrayDataType(timestampDataType(precision));
        }
        else {
            return arrayDataType(timestampDataType(precision), format("timestamp(%d)[]", precision));
        }
    }

    @DataProvider
    public Object[][] testTimestampDataProvider()
    {
        return new Object[][] {
                {true, UTC},
                {false, UTC},

                {true, jvmZone},
                {false, jvmZone},

                // using two non-JVM zones so that we don't need to worry what Postgres system zone is
                {true, vilnius},
                {false, vilnius},

                {true, kathmandu},
                {false, kathmandu},

                {true, ZoneId.of(TestingSession.DEFAULT_TIME_ZONE_KEY.getId())},
                {false, ZoneId.of(TestingSession.DEFAULT_TIME_ZONE_KEY.getId())},
        };
    }

    /**
     * @see #testTimestampWithTimeZoneCoercion
     */
    @Test(dataProvider = "trueFalse", dataProviderClass = DataProviders.class)
    public void testTimestampWithTimeZone(boolean insertWithTrino)
    {
        DataTypeTest tests = DataTypeTest.create(true);
        for (int precision : List.of(3, 6)) {
            // test all standard cases with precision 3 and 6 to make sure the long and short TIMESTAMP WITH TIME ZONE
            // is gap friendly.
            DataType<ZonedDateTime> dataType = timestampWithTimeZoneDataType(precision, insertWithTrino);
            tests.addRoundTrip(dataType, epoch.atZone(UTC));
            tests.addRoundTrip(dataType, epoch.atZone(kathmandu));
            tests.addRoundTrip(dataType, epoch.atZone(fixedOffsetEast));
            tests.addRoundTrip(dataType, epoch.atZone(fixedOffsetWest));
            tests.addRoundTrip(dataType, beforeEpoch.atZone(UTC));
            tests.addRoundTrip(dataType, beforeEpoch.atZone(kathmandu));
            tests.addRoundTrip(dataType, beforeEpoch.atZone(fixedOffsetEast));
            tests.addRoundTrip(dataType, beforeEpoch.atZone(fixedOffsetWest));
            tests.addRoundTrip(dataType, afterEpoch.atZone(UTC));
            tests.addRoundTrip(dataType, afterEpoch.atZone(kathmandu));
            tests.addRoundTrip(dataType, afterEpoch.atZone(fixedOffsetEast));
            tests.addRoundTrip(dataType, afterEpoch.atZone(fixedOffsetWest));
            tests.addRoundTrip(dataType, afterEpoch.atZone(ZoneId.of("GMT")));
            tests.addRoundTrip(dataType, afterEpoch.atZone(ZoneId.of("UTC")));
            tests.addRoundTrip(dataType, afterEpoch.atZone(ZoneId.of("UTC+00:00")));
            tests.addRoundTrip(dataType, timeDoubledInJvmZone.atZone(UTC));
            tests.addRoundTrip(dataType, timeDoubledInJvmZone.atZone(jvmZone));
            tests.addRoundTrip(dataType, timeDoubledInJvmZone.atZone(kathmandu));
            tests.addRoundTrip(dataType, timeDoubledInVilnius.atZone(UTC));
            tests.addRoundTrip(dataType, timeDoubledInVilnius.atZone(vilnius));
            tests.addRoundTrip(dataType, timeDoubledInVilnius.atZone(kathmandu));
            tests.addRoundTrip(dataType, timeGapInJvmZone1.atZone(UTC));
            tests.addRoundTrip(dataType, timeGapInJvmZone1.atZone(kathmandu));
            tests.addRoundTrip(dataType, timeGapInJvmZone2.atZone(UTC));
            tests.addRoundTrip(dataType, timeGapInJvmZone2.atZone(kathmandu));
            tests.addRoundTrip(dataType, timeGapInVilnius.atZone(kathmandu));
            tests.addRoundTrip(dataType, timeGapInKathmandu.atZone(vilnius));
        }

        // test arbitrary time for all supported precisions
        tests.addRoundTrip(timestampWithTimeZoneDataType(1, insertWithTrino), ZonedDateTime.of(2012, 1, 2, 3, 4, 5, 100_000_000, kathmandu));
        tests.addRoundTrip(timestampWithTimeZoneDataType(2, insertWithTrino), ZonedDateTime.of(2012, 1, 2, 3, 4, 5, 120_000_000, kathmandu));
        tests.addRoundTrip(timestampWithTimeZoneDataType(3, insertWithTrino), ZonedDateTime.of(2012, 1, 2, 3, 4, 5, 123_000_000, kathmandu));
        tests.addRoundTrip(timestampWithTimeZoneDataType(4, insertWithTrino), ZonedDateTime.of(2012, 1, 2, 3, 4, 5, 123_400_000, kathmandu));
        tests.addRoundTrip(timestampWithTimeZoneDataType(5, insertWithTrino), ZonedDateTime.of(2012, 1, 2, 3, 4, 5, 123_450_000, kathmandu));
        tests.addRoundTrip(timestampWithTimeZoneDataType(6, insertWithTrino), ZonedDateTime.of(2012, 1, 2, 3, 4, 5, 123_456_000, kathmandu));

        if (insertWithTrino) {
            tests.execute(getQueryRunner(), trinoCreateAsSelect("test_timestamp_with_time_zone"));
        }
        else {
            tests.execute(getQueryRunner(), postgresCreateAndInsert("test_timestamp_with_time_zone"));
        }
    }

    /**
     * Additional test supplementing {@link #testTimestampWithTimeZone} with values that do not necessarily round-trip, including
     * timestamp precision higher than expressible with {@code ZonedDateTime}.
     *
     * @see #testTimestamp
     */
    @Test
    public void testTimestampWithTimeZoneCoercion()
    {
        SqlDataTypeTest.create()

                .addRoundTrip("TIMESTAMP '1970-01-01 00:00:00 UTC'", "TIMESTAMP '1970-01-01 00:00:00 UTC'")

                .addRoundTrip("TIMESTAMP '1970-01-01 00:00:00.1 UTC'", "TIMESTAMP '1970-01-01 00:00:00.1 UTC'")
                .addRoundTrip("TIMESTAMP '1970-01-01 00:00:00.9 UTC'", "TIMESTAMP '1970-01-01 00:00:00.9 UTC'")
                .addRoundTrip("TIMESTAMP '1970-01-01 00:00:00.123 UTC'", "TIMESTAMP '1970-01-01 00:00:00.123 UTC'")
                .addRoundTrip("TIMESTAMP '1970-01-01 00:00:00.123000 UTC'", "TIMESTAMP '1970-01-01 00:00:00.123000 UTC'")
                .addRoundTrip("TIMESTAMP '1970-01-01 00:00:00.999 UTC'", "TIMESTAMP '1970-01-01 00:00:00.999 UTC'")
                // max supported precision
                .addRoundTrip("TIMESTAMP '1970-01-01 00:00:00.123456 UTC'", "TIMESTAMP '1970-01-01 00:00:00.123456 UTC'")

                .addRoundTrip("TIMESTAMP '2020-09-27 12:34:56.1 UTC'", "TIMESTAMP '2020-09-27 12:34:56.1 UTC'")
                .addRoundTrip("TIMESTAMP '2020-09-27 12:34:56.9 UTC'", "TIMESTAMP '2020-09-27 12:34:56.9 UTC'")
                .addRoundTrip("TIMESTAMP '2020-09-27 12:34:56.123 UTC'", "TIMESTAMP '2020-09-27 12:34:56.123 UTC'")
                .addRoundTrip("TIMESTAMP '2020-09-27 12:34:56.123000 UTC'", "TIMESTAMP '2020-09-27 12:34:56.123000 UTC'")
                .addRoundTrip("TIMESTAMP '2020-09-27 12:34:56.999 UTC'", "TIMESTAMP '2020-09-27 12:34:56.999 UTC'")
                // max supported precision
                .addRoundTrip("TIMESTAMP '2020-09-27 12:34:56.123456 UTC'", "TIMESTAMP '2020-09-27 12:34:56.123456 UTC'")

                // round down
                .addRoundTrip("TIMESTAMP '1970-01-01 00:00:00.1234561 UTC'", "TIMESTAMP '1970-01-01 00:00:00.123456 UTC'")

                // nanoc round up, end result rounds down
                .addRoundTrip("TIMESTAMP '1970-01-01 00:00:00.123456499 UTC'", "TIMESTAMP '1970-01-01 00:00:00.123456 UTC'")
                .addRoundTrip("TIMESTAMP '1970-01-01 00:00:00.123456499999 UTC'", "TIMESTAMP '1970-01-01 00:00:00.123456 UTC'")

                // round up
                .addRoundTrip("TIMESTAMP '1970-01-01 00:00:00.1234565 UTC'", "TIMESTAMP '1970-01-01 00:00:00.123457 UTC'")

                // max precision
                .addRoundTrip("TIMESTAMP '1970-01-01 00:00:00.111222333444 UTC'", "TIMESTAMP '1970-01-01 00:00:00.111222 UTC'")

                // round up to next second
                .addRoundTrip("TIMESTAMP '1970-01-01 00:00:00.9999995 UTC'", "TIMESTAMP '1970-01-01 00:00:01.000000 UTC'")

                // round up to next day
                .addRoundTrip("TIMESTAMP '1970-01-01 23:59:59.9999995 UTC'", "TIMESTAMP '1970-01-02 00:00:00.000000 UTC'")

                // negative epoch
                .addRoundTrip("TIMESTAMP '1969-12-31 23:59:59.9999995 UTC'", "TIMESTAMP '1970-01-01 00:00:00.000000 UTC'")
                .addRoundTrip("TIMESTAMP '1969-12-31 23:59:59.999999499999 UTC'", "TIMESTAMP '1969-12-31 23:59:59.999999 UTC'")
                .addRoundTrip("TIMESTAMP '1969-12-31 23:59:59.9999994 UTC'", "TIMESTAMP '1969-12-31 23:59:59.999999 UTC'")

                // CTAS with Trino, where the coercion is done by the connector
                .execute(getQueryRunner(), trinoCreateAsSelect(getSession(), "test_timestamp_tz_coercion"))
                // INSERT with Trino, where the coercion is done by the engine
                .execute(getQueryRunner(), trinoCreateAndInsert(getSession(), "test_timestamp_tz_coercion"));
    }

    @Test(dataProvider = "trueFalse", dataProviderClass = DataProviders.class)
    public void testArrayTimestampWithTimeZone(boolean insertWithTrino)
    {
        DataTypeTest tests = DataTypeTest.create();
        for (int precision : List.of(3, 6)) {
            // test all standard cases with precision 3 and 6 to make sure the long and short TIMESTAMP WITH TIME ZONE
            // is gap friendly.
            DataType<List<ZonedDateTime>> dataType = arrayOfTimestampWithTimeZoneDataType(precision, insertWithTrino);

            tests.addRoundTrip(dataType, asList(epoch.atZone(UTC), epoch.atZone(kathmandu)));
            tests.addRoundTrip(dataType, asList(beforeEpoch.atZone(kathmandu), beforeEpoch.atZone(UTC)));
            tests.addRoundTrip(dataType, asList(afterEpoch.atZone(UTC), afterEpoch.atZone(kathmandu)));
            tests.addRoundTrip(dataType, asList(timeDoubledInJvmZone.atZone(UTC)));
            tests.addRoundTrip(dataType, asList(timeDoubledInJvmZone.atZone(kathmandu)));
            tests.addRoundTrip(dataType, asList(timeDoubledInVilnius.atZone(UTC), timeDoubledInVilnius.atZone(vilnius), timeDoubledInVilnius.atZone(kathmandu)));
            tests.addRoundTrip(dataType, asList(timeGapInJvmZone1.atZone(UTC), timeGapInJvmZone1.atZone(kathmandu)));
            tests.addRoundTrip(dataType, asList(timeGapInJvmZone2.atZone(UTC), timeGapInJvmZone2.atZone(kathmandu)));
            tests.addRoundTrip(dataType, asList(timeGapInVilnius.atZone(kathmandu)));
            tests.addRoundTrip(dataType, asList(timeGapInKathmandu.atZone(vilnius)));
            if (!insertWithTrino) {
                // Postgres results with non-DST time (winter time) for timeDoubledInJvmZone.atZone(jvmZone) while Java results with DST time
                // When writing timestamptz arrays, Postgres JDBC driver converts java.sql.Timestamp to string representing date-time in JVM zone
                // TODO upgrade driver or find a different way to write timestamptz array elements as a point in time values with org.postgresql.jdbc.PgArray (https://github.com/pgjdbc/pgjdbc/issues/1225#issuecomment-516312324)
                tests.addRoundTrip(dataType, asList(timeDoubledInJvmZone.atZone(jvmZone)));
            }
        }

        // test arbitrary time for all supported precisions
        tests.addRoundTrip(arrayOfTimestampWithTimeZoneDataType(1, insertWithTrino), asList(ZonedDateTime.of(2012, 1, 2, 3, 4, 5, 100_000_000, kathmandu)));
        tests.addRoundTrip(arrayOfTimestampWithTimeZoneDataType(2, insertWithTrino), asList(ZonedDateTime.of(2012, 1, 2, 3, 4, 5, 120_000_000, kathmandu)));
        tests.addRoundTrip(arrayOfTimestampWithTimeZoneDataType(3, insertWithTrino), asList(ZonedDateTime.of(2012, 1, 2, 3, 4, 5, 123_000_000, kathmandu)));
        tests.addRoundTrip(arrayOfTimestampWithTimeZoneDataType(4, insertWithTrino), asList(ZonedDateTime.of(2012, 1, 2, 3, 4, 5, 123_400_000, kathmandu)));
        tests.addRoundTrip(arrayOfTimestampWithTimeZoneDataType(5, insertWithTrino), asList(ZonedDateTime.of(2012, 1, 2, 3, 4, 5, 123_450_000, kathmandu)));
        tests.addRoundTrip(arrayOfTimestampWithTimeZoneDataType(6, insertWithTrino), asList(ZonedDateTime.of(2012, 1, 2, 3, 4, 5, 123_456_000, kathmandu)));

        if (insertWithTrino) {
            tests.execute(getQueryRunner(), sessionWithArrayAsArray(), trinoCreateAsSelect(sessionWithArrayAsArray(), "test_array_timestamp_with_time_zone"));
        }
        else {
            tests.execute(getQueryRunner(), sessionWithArrayAsArray(), postgresCreateAndInsert("test_array_timestamp_with_time_zone"));
        }
    }

    @Test
    public void testJson()
    {
        jsonTestCases(jsonDataType())
                .execute(getQueryRunner(), postgresCreateAndInsert("postgresql_test_json"));

        jsonTestCases(jsonDataType())
                .execute(getQueryRunner(), trinoCreateAsSelect("trino__test_json"));
    }

    @Test
    public void testJsonb()
    {
        jsonTestCases(jsonbDataType())
                .execute(getQueryRunner(), postgresCreateAndInsert("postgresql_test_jsonb"));
    }

    private DataTypeTest jsonTestCases(DataType<String> jsonDataType)
    {
        return DataTypeTest.create(true)
                .addRoundTrip(jsonDataType, "{}")
                .addRoundTrip(jsonDataType, null)
                .addRoundTrip(jsonDataType, "null")
                .addRoundTrip(jsonDataType, "123.4")
                .addRoundTrip(jsonDataType, "\"abc\"")
                .addRoundTrip(jsonDataType, "\"text with \\\" quotations and ' apostrophes\"")
                .addRoundTrip(jsonDataType, "\"\"")
                .addRoundTrip(jsonDataType, "{\"a\":1,\"b\":2}")
                .addRoundTrip(jsonDataType, "{\"a\":[1,2,3],\"b\":{\"aa\":11,\"bb\":[{\"a\":1,\"b\":2},{\"a\":0}]}}")
                .addRoundTrip(jsonDataType, "[]");
    }

    @Test
    public void testHstore()
    {
        hstoreTestCases(hstoreDataType())
                .execute(getQueryRunner(), postgresCreateAndInsert("postgresql_test_hstore"));

        hstoreTestCases(varcharMapDataType())
                .execute(getQueryRunner(), postgresCreateTrinoInsert("postgresql_test_hstore"));
    }

    private DataTypeTest hstoreTestCases(DataType<Map<String, String>> varcharMapDataType)
    {
        return DataTypeTest.create()
                .addRoundTrip(varcharMapDataType, null)
                .addRoundTrip(varcharMapDataType, ImmutableMap.of())
                .addRoundTrip(varcharMapDataType, ImmutableMap.of("key1", "value1"))
                .addRoundTrip(varcharMapDataType, ImmutableMap.of("key1", "value1", "key2", "value2", "key3", "value3"))
                .addRoundTrip(varcharMapDataType, ImmutableMap.of("key1", " \" ", "key2", " ' ", "key3", " ]) "))
                .addRoundTrip(varcharMapDataType, Collections.singletonMap("key1", null));
    }

    @Test
    public void testUuid()
    {
        uuidTestCases(uuidDataType())
                .execute(getQueryRunner(), postgresCreateAndInsert("postgresql_test_uuid"));

        uuidTestCases(uuidDataType())
                .execute(getQueryRunner(), trinoCreateAsSelect("trino__test_uuid"));
    }

    private DataTypeTest uuidTestCases(DataType<java.util.UUID> uuidDataType)
    {
        return DataTypeTest.create(true)
                .addRoundTrip(uuidDataType, java.util.UUID.fromString("00000000-0000-0000-0000-000000000000"))
                .addRoundTrip(uuidDataType, java.util.UUID.fromString("123e4567-e89b-12d3-a456-426655440000"));
    }

    @Test
    public void testMoney()
    {
        DataTypeTest.create(true)
                .addRoundTrip(moneyDataType(), null)
                .addRoundTrip(moneyDataType(), 10.)
                .addRoundTrip(moneyDataType(), 10.54)
                .addRoundTrip(moneyDataType(), 10_000_000.42)
                .execute(getQueryRunner(), postgresCreateAndInsert("trino_test_money"));
    }

    private void testUnsupportedDataTypeAsIgnored(String dataTypeName, String databaseValue)
    {
        testUnsupportedDataTypeAsIgnored(getSession(), dataTypeName, databaseValue);
    }

    private void testUnsupportedDataTypeAsIgnored(Session session, String dataTypeName, String databaseValue)
    {
        JdbcSqlExecutor jdbcSqlExecutor = new JdbcSqlExecutor(postgreSqlServer.getJdbcUrl(), postgreSqlServer.getProperties());
        try (TestTable table = new TestTable(
                jdbcSqlExecutor,
                "unsupported_type",
                format("(key varchar(5), unsupported_column %s)", dataTypeName),
                ImmutableList.of(
                        "'1', NULL",
                        "'2', " + databaseValue))) {
            assertQuery(session, "SELECT * FROM " + table.getName(), "VALUES 1, 2");
            assertQuery(
                    session,
                    "DESC " + table.getName(),
                    "VALUES ('key', 'varchar(5)','', '')"); // no 'unsupported_column'

            assertUpdate(session, format("INSERT INTO %s VALUES '3'", table.getName()), 1);
            assertQuery(session, "SELECT * FROM " + table.getName(), "VALUES '1', '2', '3'");
        }
    }

    private void testUnsupportedDataTypeConvertedToVarchar(Session session, String dataTypeName, String internalDataTypeName, String databaseValue, String trinoValue)
    {
        JdbcSqlExecutor jdbcSqlExecutor = new JdbcSqlExecutor(postgreSqlServer.getJdbcUrl(), postgreSqlServer.getProperties());
        try (TestTable table = new TestTable(
                jdbcSqlExecutor,
                "unsupported_type",
                format("(key varchar(5), unsupported_column %s)", dataTypeName),
                ImmutableList.of(
                        "1, NULL",
                        "2, " + databaseValue))) {
            Session convertToVarchar = Session.builder(session)
                    .setCatalogSessionProperty("postgresql", UNSUPPORTED_TYPE_HANDLING, CONVERT_TO_VARCHAR.name())
                    .build();
            assertQuery(
                    convertToVarchar,
                    "SELECT * FROM " + table.getName(),
                    format("VALUES ('1', NULL), ('2', %s)", trinoValue));
            assertQuery(
                    convertToVarchar,
                    format("SELECT key FROM %s WHERE unsupported_column = %s", table.getName(), trinoValue),
                    "VALUES '2'");
            assertQuery(
                    convertToVarchar,
                    "DESC " + table.getName(),
                    "VALUES " +
                            "('key', 'varchar(5)', '', ''), " +
                            "('unsupported_column', 'varchar', '', '')");
            assertUpdate(
                    convertToVarchar,
                    format("INSERT INTO %s (key, unsupported_column) VALUES ('3', NULL)", table.getName()),
                    1);
            assertQueryFails(
                    convertToVarchar,
                    format("INSERT INTO %s (key, unsupported_column) VALUES ('4', %s)", table.getName(), trinoValue),
                    "\\QUnderlying type that is mapped to VARCHAR is not supported for INSERT: " + internalDataTypeName);
            assertUpdate(
                    convertToVarchar,
                    format("INSERT INTO %s (key) VALUES '5'", table.getName()),
                    1);
            assertQuery(
                    convertToVarchar,
                    "SELECT * FROM " + table.getName(),
                    format("VALUES ('1', NULL), ('2', %s), ('3', NULL), ('5', NULL)", trinoValue));
        }
    }

    public static DataType<ZonedDateTime> timestampWithTimeZoneDataType(int precision, boolean insertWithTrino)
    {
        if (insertWithTrino) {
            return trinoTimestampWithTimeZoneDataType(precision);
        }
        else {
            return postgreSqlTimestampWithTimeZoneDataType(precision);
        }
    }

    public static DataType<ZonedDateTime> trinoTimestampWithTimeZoneDataType(int precision)
    {
        return dataType(
                format("timestamp(%d) with time zone", precision),
                createTimestampWithTimeZoneType(precision),
                zonedDateTime -> DateTimeFormatter.ofPattern("'TIMESTAMP '''uuuu-MM-dd HH:mm:ss.SSSSSS VV''").format(zonedDateTime),
                // PostgreSQL does not store zone, only the point in time
                zonedDateTime -> zonedDateTime.withZoneSameInstant(ZoneId.of("UTC")));
    }

    public static DataType<ZonedDateTime> postgreSqlTimestampWithTimeZoneDataType(int precision)
    {
        return dataType(
                format("timestamp(%d) with time zone", precision),
                createTimestampWithTimeZoneType(precision),
                // PostgreSQL never examines the content of a literal string before determining its type, so `TIMESTAMP '.... {zone}'` won't work.
                // PostgreSQL does not store zone, only the point in time
                zonedDateTime -> {
                    String pattern = format("'TIMESTAMP (%d) WITH TIME ZONE '''uuuu-MM-dd HH:mm:ss.SSSSSS VV''", precision);
                    return DateTimeFormatter.ofPattern(pattern).format(zonedDateTime.withZoneSameInstant(UTC));
                },
                zonedDateTime -> DateTimeFormatter.ofPattern("'TIMESTAMP '''uuuu-MM-dd HH:mm:ss.SSSSSS VV''").format(zonedDateTime),
                zonedDateTime -> zonedDateTime.withZoneSameInstant(ZoneId.of("UTC")));
    }

    public static DataType<List<ZonedDateTime>> arrayOfTimestampWithTimeZoneDataType(int precision, boolean insertWithTrino)
    {
        if (insertWithTrino) {
            return arrayDataType(trinoTimestampWithTimeZoneDataType(precision));
        }
        else {
            return arrayDataType(postgreSqlTimestampWithTimeZoneDataType(precision), format("timestamptz(%d)[]", precision));
        }
    }

    public static DataType<String> jsonbDataType()
    {
        return dataType(
                "jsonb",
                JSON,
                value -> "JSON " + formatStringLiteral(value));
    }

    private DataType<Map<String, String>> hstoreDataType()
    {
        return dataType(
                "hstore",
                getQueryRunner().getTypeManager().getType(mapType(VARCHAR.getTypeSignature(), VARCHAR.getTypeSignature())),
                TestPostgreSqlTypeMapping::hstoreLiteral);
    }

    private static String hstoreLiteral(Map<String, String> value)
    {
        return value.entrySet().stream()
                .flatMap(entry -> Stream.of(entry.getKey(), entry.getValue()))
                .map(input -> (input == null) ? "null" : formatStringLiteral(input))
                .collect(joining(",", "hstore(ARRAY[", "]::varchar[])"));
    }

    private DataType<Map<String, String>> varcharMapDataType()
    {
        return dataType(
                "hstore",
                getQueryRunner().getTypeManager().getType(mapType(VARCHAR.getTypeSignature(), VARCHAR.getTypeSignature())),
                value -> {
                    List<String> formatted = value.entrySet().stream()
                            .flatMap(entry -> Stream.of(entry.getKey(), entry.getValue()))
                            .map(string -> {
                                if (string == null) {
                                    return "null";
                                }
                                return DataType.formatStringLiteral(string);
                            })
                            .collect(toImmutableList());
                    ImmutableList.Builder<String> keys = ImmutableList.builder();
                    ImmutableList.Builder<String> values = ImmutableList.builder();
                    for (int i = 0; i < formatted.size(); i = i + 2) {
                        keys.add(formatted.get(i));
                        values.add(formatted.get(i + 1));
                    }
                    return format("MAP(ARRAY[%s], ARRAY[%s])", Joiner.on(',').join(keys.build()), Joiner.on(',').join(values.build()));
                });
    }

    public static DataType<java.util.UUID> uuidDataType()
    {
        return dataType(
                "uuid",
                UUID,
                value -> "UUID " + formatStringLiteral(value.toString()));
    }

    private static DataType<Double> moneyDataType()
    {
        return dataType(
                "money",
                VARCHAR,
                String::valueOf,
                amount -> {
                    NumberFormat numberFormat = NumberFormat.getCurrencyInstance(Locale.US);
                    return "'" + numberFormat.format(amount) + "'";
                },
                amount -> {
                    NumberFormat numberFormat = NumberFormat.getCurrencyInstance(Locale.US);
                    return numberFormat.format(amount);
                });
    }

    private Session sessionWithArrayAsArray()
    {
        return Session.builder(getSession())
                .setSystemProperty("postgresql.array_mapping", AS_ARRAY.name())
                .build();
    }

    private Session sessionWithDecimalMappingAllowOverflow(RoundingMode roundingMode, int scale)
    {
        return Session.builder(getSession())
                .setCatalogSessionProperty("postgresql", DECIMAL_MAPPING, ALLOW_OVERFLOW.name())
                .setCatalogSessionProperty("postgresql", DECIMAL_ROUNDING_MODE, roundingMode.name())
                .setCatalogSessionProperty("postgresql", DECIMAL_DEFAULT_SCALE, Integer.valueOf(scale).toString())
                .build();
    }

    private Session sessionWithDecimalMappingStrict(UnsupportedTypeHandling unsupportedTypeHandling)
    {
        return Session.builder(getSession())
                .setCatalogSessionProperty("postgresql", DECIMAL_MAPPING, STRICT.name())
                .setCatalogSessionProperty("postgresql", UNSUPPORTED_TYPE_HANDLING, unsupportedTypeHandling.name())
                .build();
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

    private DataSetup postgresCreateAndInsert(String tableNamePrefix)
    {
        return new CreateAndInsertDataSetup(new JdbcSqlExecutor(postgreSqlServer.getJdbcUrl(), postgreSqlServer.getProperties()), tableNamePrefix);
    }

    private DataSetup postgresCreateTrinoInsert(String tableNamePrefix)
    {
        return new CreateAndTrinoInsertDataSetup(new JdbcSqlExecutor(postgreSqlServer.getJdbcUrl(), postgreSqlServer.getProperties()), new TrinoSqlExecutor(getQueryRunner()), tableNamePrefix);
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
}
