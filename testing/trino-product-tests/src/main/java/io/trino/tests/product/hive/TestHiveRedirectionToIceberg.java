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
package io.trino.tests.product.hive;

import io.trino.tempto.BeforeTestWithContext;
import io.trino.tempto.ProductTest;
import io.trino.tempto.assertions.QueryAssert;
import io.trino.tempto.query.QueryResult;
import org.assertj.core.api.AbstractStringAssert;
import org.assertj.core.api.Assertions;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.Iterables.getOnlyElement;
import static io.trino.tempto.assertions.QueryAssert.Row.row;
import static io.trino.tempto.assertions.QueryAssert.assertQueryFailure;
import static io.trino.tempto.assertions.QueryAssert.assertThat;
import static io.trino.tempto.query.QueryExecutor.param;
import static io.trino.tests.product.TestGroups.HIVE_ICEBERG_REDIRECTIONS;
import static io.trino.tests.product.TestGroups.PROFILE_SPECIFIC_TESTS;
import static io.trino.tests.product.hive.util.TemporaryHiveTable.randomTableSuffix;
import static io.trino.tests.product.utils.QueryExecutors.onTrino;
import static java.lang.String.format;
import static java.sql.JDBCType.VARCHAR;

public class TestHiveRedirectionToIceberg
        extends ProductTest
{
    @BeforeTestWithContext
    public void createAdditionalSchema()
    {
        onTrino().executeQuery("CREATE SCHEMA IF NOT EXISTS hive.nondefaultschema");
    }

    @Test(groups = {HIVE_ICEBERG_REDIRECTIONS, PROFILE_SPECIFIC_TESTS})
    public void testRedirect()
    {
        String tableName = "redirect_" + randomTableSuffix();
        String hiveTableName = "hive.default." + tableName;
        String icebergTableName = "iceberg.default." + tableName;

        createIcebergTable(icebergTableName, false);

        assertResultsEqual(
                onTrino().executeQuery("TABLE " + icebergTableName),
                onTrino().executeQuery("TABLE " + hiveTableName));

        onTrino().executeQuery("DROP TABLE " + icebergTableName);
    }

    @Test(groups = {HIVE_ICEBERG_REDIRECTIONS, PROFILE_SPECIFIC_TESTS})
    public void testRedirectWithNonDefaultSchema()
    {
        String tableName = "redirect_non_default_schema_" + randomTableSuffix();
        String hiveTableName = "hive.nondefaultschema." + tableName;
        String icebergTableName = "iceberg.nondefaultschema." + tableName;

        createIcebergTable(icebergTableName, false);

        assertResultsEqual(
                onTrino().executeQuery("TABLE " + icebergTableName),
                onTrino().executeQuery("TABLE " + hiveTableName));

        onTrino().executeQuery("DROP TABLE " + icebergTableName);
    }

    @Test(groups = {HIVE_ICEBERG_REDIRECTIONS, PROFILE_SPECIFIC_TESTS})
    public void testRedirectToNonexistentCatalog()
    {
        String tableName = "redirect_to_nonexistent_iceberg_" + randomTableSuffix();
        String hiveTableName = "hive.default." + tableName;
        String icebergTableName = "iceberg.default." + tableName;

        createIcebergTable(icebergTableName, false);

        // sanity check
        assertResultsEqual(
                onTrino().executeQuery("TABLE " + icebergTableName),
                onTrino().executeQuery("TABLE " + hiveTableName));

        onTrino().executeQuery("SET SESSION hive.iceberg_catalog_name = 'someweirdcatalog'");

        assertQueryFailure(() -> onTrino().executeQuery("TABLE " + hiveTableName))
                .hasMessageMatching(".*Table 'hive.default.redirect_to_nonexistent_iceberg_.*' redirected to 'someweirdcatalog.default.redirect_to_nonexistent_iceberg_.*', but the target catalog 'someweirdcatalog' does not exist");

        onTrino().executeQuery("DROP TABLE " + icebergTableName);
    }

    // Note: this tests engine more than connectors. Still good scenario to test.
    @Test(groups = {HIVE_ICEBERG_REDIRECTIONS, PROFILE_SPECIFIC_TESTS})
    public void testRedirectWithDefaultSchemaInSession()
    {
        String tableName = "redirect_with_use_" + randomTableSuffix();
        String hiveTableName = "hive.default." + tableName;
        String icebergTableName = "iceberg.default." + tableName;

        createIcebergTable(icebergTableName, false);

        onTrino().executeQuery("USE iceberg.default");
        assertResultsEqual(
                onTrino().executeQuery("TABLE " + tableName), // unqualified
                onTrino().executeQuery("TABLE " + hiveTableName));

        onTrino().executeQuery("USE hive.default");
        assertResultsEqual(
                onTrino().executeQuery("TABLE " + icebergTableName),
                onTrino().executeQuery("TABLE " + tableName)); // unqualified

        onTrino().executeQuery("DROP TABLE " + icebergTableName);
    }

    @Test(groups = {HIVE_ICEBERG_REDIRECTIONS, PROFILE_SPECIFIC_TESTS})
    public void testRedirectPartitionsToUnpartitioned()
    {
        String tableName = "iceberg_unpartitioned_table_" + randomTableSuffix();
        String icebergTableName = "iceberg.default." + tableName;

        createIcebergTable(icebergTableName, false);

        assertThat(onTrino().executeQuery("" +
                "SELECT record_count, data.nationkey.min, data.nationkey.max, data.name.min, data.name.max " +
                "FROM hive.default.\"" + tableName + "$partitions\""))
                .containsOnly(row(25L, 0L, 24L, "ALGERIA", "VIETNAM"));

        onTrino().executeQuery("DROP TABLE " + icebergTableName);
    }

    @Test(groups = {HIVE_ICEBERG_REDIRECTIONS, PROFILE_SPECIFIC_TESTS})
    public void testRedirectPartitionsToPartitioned()
    {
        String tableName = "iceberg_partitioned_table_" + randomTableSuffix();
        String icebergTableName = "iceberg.default." + tableName;

        createIcebergTable(icebergTableName, true);

        assertThat(onTrino().executeQuery("" +
                "SELECT partition.regionkey, record_count, data.nationkey.min, data.nationkey.max, data.name.min, data.name.max " +
                "FROM hive.default.\"" + tableName + "$partitions\""))
                .containsOnly(
                        row(0L, 5L, 0L, 16L, "ALGERIA", "MOZAMBIQUE"),
                        row(1L, 5L, 1L, 24L, "ARGENTINA", "UNITED STATES"),
                        row(2L, 5L, 8L, 21L, "CHINA", "VIETNAM"),
                        row(3L, 5L, 6L, 23L, "FRANCE", "UNITED KINGDOM"),
                        row(4L, 5L, 4L, 20L, "EGYPT", "SAUDI ARABIA"));

        onTrino().executeQuery("DROP TABLE " + icebergTableName);
    }

    @Test(groups = {HIVE_ICEBERG_REDIRECTIONS, PROFILE_SPECIFIC_TESTS}, dataProvider = "schemaAndPartitioning")
    public void testInsert(String schema, boolean partitioned)
    {
        String tableName = "iceberg_insert_" + randomTableSuffix();
        String hiveTableName = "hive." + schema + "." + tableName;
        String icebergTableName = "iceberg." + schema + "." + tableName;

        createIcebergTable(icebergTableName, partitioned, false);

        onTrino().executeQuery("INSERT INTO " + hiveTableName + " VALUES (42, 'some name', 12, 'some comment')");

        assertThat(onTrino().executeQuery("TABLE " + hiveTableName))
                .containsOnly(row(42L, "some name", 12L, "some comment"));
        assertThat(onTrino().executeQuery("TABLE " + icebergTableName))
                .containsOnly(row(42L, "some name", 12L, "some comment"));

        onTrino().executeQuery("DROP TABLE " + icebergTableName);
    }

    @DataProvider
    public static Object[][] schemaAndPartitioning()
    {
        return new Object[][] {
                {"default", false},
                {"default", true},
                // Note: this tests engine more than connectors. Still good scenario to test.
                {"nondefaultschema", false},
                {"nondefaultschema", true},
        };
    }

    @Test(groups = {HIVE_ICEBERG_REDIRECTIONS, PROFILE_SPECIFIC_TESTS})
    public void testDelete()
    {
        String tableName = "iceberg_insert_" + randomTableSuffix();
        String hiveTableName = "hive.default." + tableName;
        String icebergTableName = "iceberg.default." + tableName;

        createIcebergTable(icebergTableName, true);

        onTrino().executeQuery("DELETE FROM " + hiveTableName + " WHERE regionkey = 1");

        assertResultsEqual(
                onTrino().executeQuery("TABLE " + icebergTableName),
                onTrino().executeQuery("SELECT nationkey, name, regionkey, comment FROM tpch.tiny.nation WHERE regionkey != 1"));

        onTrino().executeQuery("DROP TABLE " + icebergTableName);
    }

    @Test(groups = {HIVE_ICEBERG_REDIRECTIONS, PROFILE_SPECIFIC_TESTS})
    public void testUpdate()
    {
        String tableName = "iceberg_insert_" + randomTableSuffix();
        String hiveTableName = "hive.default." + tableName;
        String icebergTableName = "iceberg.default." + tableName;

        createIcebergTable(icebergTableName, true);

        assertQueryFailure(() -> onTrino().executeQuery("UPDATE " + hiveTableName + " SET nationkey = nationkey + 100 WHERE regionkey = 1"))
                .hasMessageMatching("\\QQuery failed (#\\E\\S+\\Q): This connector does not support updates");

        onTrino().executeQuery("DROP TABLE " + icebergTableName);
    }

    @Test(groups = {HIVE_ICEBERG_REDIRECTIONS, PROFILE_SPECIFIC_TESTS})
    public void testDropTable()
    {
        String tableName = "hive_drop_iceberg_" + randomTableSuffix();
        String hiveTableName = "hive.default." + tableName;
        String icebergTableName = "iceberg.default." + tableName;

        createIcebergTable(icebergTableName, false);
        onTrino().executeQuery("DROP TABLE " + hiveTableName);
        assertQueryFailure(() -> onTrino().executeQuery("TABLE " + icebergTableName))
                .hasMessageMatching("\\QQuery failed (#\\E\\S+\\Q): line 1:1: Table '" + icebergTableName + "' does not exist");
    }

    @Test(groups = {HIVE_ICEBERG_REDIRECTIONS, PROFILE_SPECIFIC_TESTS})
    public void testDescribe()
    {
        String tableName = "iceberg_describe_" + randomTableSuffix();
        String hiveTableName = "hive.default." + tableName;
        String icebergTableName = "iceberg.default." + tableName;

        createIcebergTable(icebergTableName, true);

        assertResultsEqual(
                onTrino().executeQuery("DESCRIBE " + icebergTableName),
                onTrino().executeQuery("DESCRIBE " + hiveTableName));

        onTrino().executeQuery("DROP TABLE " + icebergTableName);
    }

    @Test(groups = {HIVE_ICEBERG_REDIRECTIONS, PROFILE_SPECIFIC_TESTS})
    public void testShowCreateTable()
    {
        String tableName = "iceberg_show_create_table_" + randomTableSuffix();
        String hiveTableName = "hive.default." + tableName;
        String icebergTableName = "iceberg.default." + tableName;

        createIcebergTable(icebergTableName, true);

        assertThat(onTrino().executeQuery("SHOW CREATE TABLE " + hiveTableName))
                .containsOnly(row("CREATE TABLE " + hiveTableName + " (\n" +
                        "   nationkey bigint,\n" +
                        "   name varchar,\n" +
                        "   regionkey bigint,\n" +
                        "   comment varchar\n" +
                        ")\n" +
                        "WITH (\n" +
                        "   format = 'ORC',\n" +
                        "   partitioning = ARRAY['regionkey']\n" + // 'partitioning' comes from Iceberg
                        ")"));

        onTrino().executeQuery("DROP TABLE " + icebergTableName);
    }

    @Test(groups = {HIVE_ICEBERG_REDIRECTIONS, PROFILE_SPECIFIC_TESTS})
    public void testShowStats()
    {
        String tableName = "iceberg_show_create_table_" + randomTableSuffix();
        String hiveTableName = "hive.default." + tableName;
        String icebergTableName = "iceberg.default." + tableName;

        createIcebergTable(icebergTableName, true);

        assertThat(onTrino().executeQuery("SHOW STATS FOR " + hiveTableName))
                .containsOnly(
                        row("nationkey", null, null, 0d, null, "0", "24"),
                        row("name", null, null, 0d, null, null, null),
                        row("regionkey", null, null, 0d, null, "0", "4"),
                        row("comment", null, null, 0d, null, null, null),
                        row(null, null, null, null, 25d, null, null));

        onTrino().executeQuery("DROP TABLE " + icebergTableName);
    }

    @Test(groups = {HIVE_ICEBERG_REDIRECTIONS, PROFILE_SPECIFIC_TESTS})
    public void testAlterTableRename()
    {
        String tableName = "iceberg_rename_table_" + randomTableSuffix();
        String hiveTableName = "hive.default." + tableName;
        String icebergTableName = "iceberg.default." + tableName;

        createIcebergTable(icebergTableName, false);

        onTrino().executeQuery("ALTER TABLE " + hiveTableName + " RENAME TO " + tableName + "_new");

        assertQueryFailure(() -> onTrino().executeQuery("TABLE " + hiveTableName))
                .hasMessageMatching("\\QQuery failed (#\\E\\S+\\Q): line 1:1: Table '" + hiveTableName + "' does not exist");
        assertQueryFailure(() -> onTrino().executeQuery("TABLE " + icebergTableName))
                .hasMessageMatching("\\QQuery failed (#\\E\\S+\\Q): line 1:1: Table '" + icebergTableName + "' does not exist");

        assertResultsEqual(
                onTrino().executeQuery("TABLE " + icebergTableName + "_new"),
                onTrino().executeQuery("TABLE " + hiveTableName + "_new"));

        onTrino().executeQuery("DROP TABLE " + icebergTableName + "_new");
    }

    @Test(groups = {HIVE_ICEBERG_REDIRECTIONS, PROFILE_SPECIFIC_TESTS})
    public void testAlterTableAddColumn()
    {
        String tableName = "iceberg_alter_table_" + randomTableSuffix();
        String hiveTableName = "hive.default." + tableName;
        String icebergTableName = "iceberg.default." + tableName;

        createIcebergTable(icebergTableName, false);

        onTrino().executeQuery("ALTER TABLE " + hiveTableName + " ADD COLUMN some_new_column double");

        // TODO: ALTER TABLE succeeded, but new column was not added
        Assertions.assertThat(onTrino().executeQuery("DESCRIBE " + icebergTableName).column(1))
                .containsOnly("nationkey", "name", "regionkey", "comment");

        assertResultsEqual(
                onTrino().executeQuery("TABLE " + icebergTableName),
                onTrino().executeQuery("SELECT * /*, NULL*/ FROM tpch.tiny.nation"));

        onTrino().executeQuery("DROP TABLE " + icebergTableName);
    }

    @Test(groups = {HIVE_ICEBERG_REDIRECTIONS, PROFILE_SPECIFIC_TESTS})
    public void testCommentTable()
    {
        String tableName = "iceberg_comment_table_" + randomTableSuffix();
        String hiveTableName = "hive.default." + tableName;
        String icebergTableName = "iceberg.default." + tableName;

        createIcebergTable(icebergTableName, false);

        assertTableComment("hive", "default", tableName).isNull();
        assertTableComment("iceberg", "default", tableName).isNull();

        onTrino().executeQuery("COMMENT ON TABLE " + hiveTableName + " IS 'This is my table, there are many like it but this one is mine'");

        // TODO: COMMENT ON TABLE succeeded, but comment was not preserved
        assertTableComment("hive", "default", tableName).isNull();
        assertTableComment("iceberg", "default", tableName).isNull();

        onTrino().executeQuery("DROP TABLE " + icebergTableName);
    }

    @Test(groups = {HIVE_ICEBERG_REDIRECTIONS, PROFILE_SPECIFIC_TESTS})
    public void testInformationSchemaColumns()
    {
        // use dedicated schema so that we control the number and shape of tables
        String schemaName = "redirect_information_schema_" + randomTableSuffix();
        onTrino().executeQuery("CREATE SCHEMA IF NOT EXISTS hive." + schemaName);

        String tableName = "redirect_information_schema_table_" + randomTableSuffix();
        String icebergTableName = "iceberg." + schemaName + "." + tableName;

        createIcebergTable(icebergTableName, false);

        // via redirection with table filter
        assertThat(onTrino().executeQuery(
                format("SELECT * FROM hive.information_schema.columns WHERE table_schema = '%s' AND table_name = '%s'", schemaName, tableName)))
                .containsOnly(
                        row("hive", schemaName, tableName, "nationkey", 1, null, "YES", "bigint"),
                        row("hive", schemaName, tableName, "name", 2, null, "YES", "varchar"),
                        row("hive", schemaName, tableName, "regionkey", 3, null, "YES", "bigint"),
                        row("hive", schemaName, tableName, "comment", 4, null, "YES", "varchar"));

        // test via redirection with just schema filter
        assertThat(onTrino().executeQuery(
                format("SELECT * FROM hive.information_schema.columns WHERE table_schema = '%s'", schemaName)))
                .containsOnly(
                        row("hive", schemaName, tableName, "nationkey", 1, null, "YES", "bigint"),
                        row("hive", schemaName, tableName, "name", 2, null, "YES", "varchar"),
                        row("hive", schemaName, tableName, "regionkey", 3, null, "YES", "bigint"),
                        row("hive", schemaName, tableName, "comment", 4, null, "YES", "varchar"));

        // sanity check that getting columns info without redirection produces matching result
        assertThat(onTrino().executeQuery(
                format("SELECT * FROM iceberg.information_schema.columns WHERE table_schema = '%s' AND table_name = '%s'", schemaName, tableName)))
                .containsOnly(
                        row("iceberg", schemaName, tableName, "nationkey", 1, null, "YES", "bigint"),
                        row("iceberg", schemaName, tableName, "name", 2, null, "YES", "varchar"),
                        row("iceberg", schemaName, tableName, "regionkey", 3, null, "YES", "bigint"),
                        row("iceberg", schemaName, tableName, "comment", 4, null, "YES", "varchar"));

        onTrino().executeQuery("DROP TABLE " + icebergTableName);
        onTrino().executeQuery("DROP SCHEMA hive." + schemaName);
    }

    @Test(groups = {HIVE_ICEBERG_REDIRECTIONS, PROFILE_SPECIFIC_TESTS})
    public void testSystemJdbcColumns()
    {
        // use dedicated schema so that we control the number and shape of tables
        String schemaName = "redirect_system_jdbc_columns_" + randomTableSuffix();
        onTrino().executeQuery("CREATE SCHEMA IF NOT EXISTS hive." + schemaName);

        String tableName = "redirect_system_jdbc_columns_table_" + randomTableSuffix();
        String icebergTableName = "iceberg." + schemaName + "." + tableName;

        createIcebergTable(icebergTableName, false);

        // via redirection with table filter
        assertThat(onTrino().executeQuery(
                format("SELECT table_cat, table_schem, table_name, column_name FROM system.jdbc.columns WHERE table_cat = 'hive' AND table_schem = '%s' AND table_name = '%s'", schemaName, tableName)))
                .containsOnly(
                        row("hive", schemaName, tableName, "nationkey"),
                        row("hive", schemaName, tableName, "name"),
                        row("hive", schemaName, tableName, "regionkey"),
                        row("hive", schemaName, tableName, "comment"));

        // test via redirection with just schema filter
        // via redirection with table filter
        assertThat(onTrino().executeQuery(
                format("SELECT table_cat, table_schem, table_name, column_name FROM system.jdbc.columns WHERE table_cat = 'hive' AND table_schem = '%s'", schemaName)))
                .containsOnly(
                        row("hive", schemaName, tableName, "nationkey"),
                        row("hive", schemaName, tableName, "name"),
                        row("hive", schemaName, tableName, "regionkey"),
                        row("hive", schemaName, tableName, "comment"));

        // sanity check that getting columns info without redirection produces matching result
        assertThat(onTrino().executeQuery(
                format("SELECT table_cat, table_schem, table_name, column_name FROM system.jdbc.columns WHERE table_cat = 'iceberg' AND table_schem = '%s' AND table_name = '%s'", schemaName, tableName)))
                .containsOnly(
                        row("iceberg", schemaName, tableName, "nationkey"),
                        row("iceberg", schemaName, tableName, "name"),
                        row("iceberg", schemaName, tableName, "regionkey"),
                        row("iceberg", schemaName, tableName, "comment"));

        onTrino().executeQuery("DROP TABLE " + icebergTableName);
        onTrino().executeQuery("DROP SCHEMA hive." + schemaName);
    }

    private static void createIcebergTable(String tableName, boolean partitioned)
    {
        createIcebergTable(tableName, partitioned, true);
    }

    private static void createIcebergTable(String tableName, boolean partitioned, boolean withData)
    {
        onTrino().executeQuery(
                "CREATE TABLE " + tableName + " " +
                        (partitioned ? "WITH (partitioning = ARRAY['regionkey']) " : "") +
                        " AS " +
                        "SELECT * FROM tpch.tiny.nation " +
                        (withData ? "WITH DATA" : "WITH NO DATA"));
    }

    private static AbstractStringAssert<?> assertTableComment(String catalog, String schema, String tableName)
    {
        QueryResult queryResult = readTableComment(catalog, schema, tableName);
        return Assertions.assertThat((String) getOnlyElement(getOnlyElement(queryResult.rows())));
    }

    private static QueryResult readTableComment(String catalog, String schema, String tableName)
    {
        return onTrino().executeQuery(
                "SELECT comment FROM system.metadata.table_comments WHERE catalog_name = ? AND schema_name = ? AND table_name = ?",
                param(VARCHAR, catalog),
                param(VARCHAR, schema),
                param(VARCHAR, tableName));
    }

    private static void assertResultsEqual(QueryResult first, QueryResult second)
    {
        assertThat(first).containsOnly(second.rows().stream()
                .map(QueryAssert.Row::new)
                .collect(toImmutableList()));

        // just for symmetry
        assertThat(second).containsOnly(first.rows().stream()
                .map(QueryAssert.Row::new)
                .collect(toImmutableList()));
    }
}
