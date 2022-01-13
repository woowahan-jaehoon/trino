
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
package io.trino.execution;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import io.trino.Session;
import io.trino.connector.CatalogName;
import io.trino.execution.warnings.WarningCollector;
import io.trino.metadata.AbstractMockMetadata;
import io.trino.metadata.AnalyzePropertyManager;
import io.trino.metadata.Catalog;
import io.trino.metadata.CatalogManager;
import io.trino.metadata.MaterializedViewDefinition;
import io.trino.metadata.MaterializedViewPropertyManager;
import io.trino.metadata.MetadataManager;
import io.trino.metadata.QualifiedObjectName;
import io.trino.metadata.TableHandle;
import io.trino.metadata.TableMetadata;
import io.trino.metadata.TablePropertyManager;
import io.trino.metadata.TableSchema;
import io.trino.metadata.ViewDefinition;
import io.trino.plugin.base.security.AllowAllSystemAccessControl;
import io.trino.security.AccessControl;
import io.trino.security.AllowAllAccessControl;
import io.trino.spi.TrinoException;
import io.trino.spi.connector.ColumnHandle;
import io.trino.spi.connector.ColumnMetadata;
import io.trino.spi.connector.ConnectorTableMetadata;
import io.trino.spi.connector.SchemaTableName;
import io.trino.spi.connector.TestingColumnHandle;
import io.trino.spi.resourcegroups.ResourceGroupId;
import io.trino.spi.security.AccessDeniedException;
import io.trino.sql.PlannerContext;
import io.trino.sql.analyzer.AnalyzerFactory;
import io.trino.sql.analyzer.StatementAnalyzerFactory;
import io.trino.sql.parser.SqlParser;
import io.trino.sql.planner.TestingConnectorTransactionHandle;
import io.trino.sql.rewrite.StatementRewrite;
import io.trino.sql.tree.AllColumns;
import io.trino.sql.tree.CreateMaterializedView;
import io.trino.sql.tree.Identifier;
import io.trino.sql.tree.Property;
import io.trino.sql.tree.QualifiedName;
import io.trino.sql.tree.StringLiteral;
import io.trino.testing.TestingAccessControlManager;
import io.trino.testing.TestingMetadata.TestingTableHandle;
import io.trino.transaction.TransactionManager;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;
import static io.airlift.concurrent.MoreFutures.getFutureValue;
import static io.trino.metadata.MetadataManager.createTestMetadataManager;
import static io.trino.spi.StandardErrorCode.ALREADY_EXISTS;
import static io.trino.spi.StandardErrorCode.INVALID_MATERIALIZED_VIEW_PROPERTY;
import static io.trino.spi.session.PropertyMetadata.stringProperty;
import static io.trino.spi.type.BigintType.BIGINT;
import static io.trino.spi.type.SmallintType.SMALLINT;
import static io.trino.sql.QueryUtil.selectList;
import static io.trino.sql.QueryUtil.simpleQuery;
import static io.trino.sql.QueryUtil.table;
import static io.trino.sql.analyzer.StatementAnalyzerFactory.createTestingStatementAnalyzerFactory;
import static io.trino.sql.planner.TestingPlannerContext.plannerContextBuilder;
import static io.trino.testing.TestingAccessControlManager.TestingPrivilegeType.CREATE_MATERIALIZED_VIEW;
import static io.trino.testing.TestingAccessControlManager.privilege;
import static io.trino.testing.TestingEventListenerManager.emptyEventListenerManager;
import static io.trino.testing.TestingSession.createBogusTestingCatalog;
import static io.trino.testing.TestingSession.testSessionBuilder;
import static io.trino.testing.assertions.TrinoExceptionAssert.assertTrinoExceptionThrownBy;
import static io.trino.transaction.InMemoryTransactionManager.createTestTransactionManager;
import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.testng.Assert.assertEquals;

@Test(singleThreaded = true)
public class TestCreateMaterializedViewTask
{
    private static final String CATALOG_NAME = "catalog";
    private static final ConnectorTableMetadata MOCK_TABLE = new ConnectorTableMetadata(
            new SchemaTableName("schema", "mock_table"),
            List.of(new ColumnMetadata("a", SMALLINT), new ColumnMetadata("b", BIGINT)),
            ImmutableMap.of("baz", "property_value"));

    private Session testSession;
    private MockMetadata metadata;
    private PlannerContext plannerContext;
    private TransactionManager transactionManager;
    private SqlParser parser;
    private QueryStateMachine queryStateMachine;
    private AnalyzerFactory analyzerFactory;
    private MaterializedViewPropertyManager materializedViewPropertyManager;

    @BeforeMethod
    public void setUp()
    {
        CatalogManager catalogManager = new CatalogManager();
        transactionManager = createTestTransactionManager(catalogManager);
        materializedViewPropertyManager = new MaterializedViewPropertyManager();
        Catalog testCatalog = createBogusTestingCatalog(CATALOG_NAME);
        catalogManager.registerCatalog(testCatalog);
        materializedViewPropertyManager.addProperties(
                testCatalog.getConnectorCatalogName(),
                ImmutableList.of(stringProperty("foo", "test materialized view property", null, false)));
        testSession = testSessionBuilder()
                .setTransactionId(transactionManager.beginTransaction(false))
                .build();
        metadata = new MockMetadata(testCatalog.getConnectorCatalogName());
        plannerContext = plannerContextBuilder().withMetadata(metadata).build();
        parser = new SqlParser();
        analyzerFactory = new AnalyzerFactory(createTestingStatementAnalyzerFactory(plannerContext, new AllowAllAccessControl(), new TablePropertyManager(), new AnalyzePropertyManager()), new StatementRewrite(ImmutableSet.of()));
        queryStateMachine = stateMachine(transactionManager, createTestMetadataManager(), new AllowAllAccessControl());
    }

    @Test
    public void testCreateMaterializedViewIfNotExists()
    {
        CreateMaterializedView statement = new CreateMaterializedView(
                Optional.empty(),
                QualifiedName.of("test_mv"),
                simpleQuery(selectList(new AllColumns()), table(QualifiedName.of("catalog", "schema", "mock_table"))),
                false,
                true,
                ImmutableList.of(),
                Optional.empty());

        getFutureValue(new CreateMaterializedViewTask(plannerContext, new AllowAllAccessControl(), parser, analyzerFactory, materializedViewPropertyManager)
                .execute(statement, queryStateMachine, ImmutableList.of(), WarningCollector.NOOP));
        assertEquals(metadata.getCreateMaterializedViewCallCount(), 1);
    }

    @Test
    public void testCreateMaterializedViewWithExistingView()
    {
        CreateMaterializedView statement = new CreateMaterializedView(
                Optional.empty(),
                QualifiedName.of("test_mv"),
                simpleQuery(selectList(new AllColumns()), table(QualifiedName.of("catalog", "schema", "mock_table"))),
                false,
                false,
                ImmutableList.of(),
                Optional.empty());

        assertTrinoExceptionThrownBy(() -> getFutureValue(new CreateMaterializedViewTask(plannerContext, new AllowAllAccessControl(), parser, analyzerFactory, materializedViewPropertyManager)
                .execute(statement, queryStateMachine, ImmutableList.of(), WarningCollector.NOOP)))
                .hasErrorCode(ALREADY_EXISTS)
                .hasMessage("Materialized view already exists");

        assertEquals(metadata.getCreateMaterializedViewCallCount(), 1);
    }

    @Test
    public void testCreateMaterializedViewWithInvalidProperty()
    {
        CreateMaterializedView statement = new CreateMaterializedView(
                Optional.empty(),
                QualifiedName.of("test_mv"),
                simpleQuery(selectList(new AllColumns()), table(QualifiedName.of("catalog", "schema", "mock_table"))),
                false,
                true,
                ImmutableList.of(new Property(new Identifier("baz"), new StringLiteral("abc"))),
                Optional.empty());

        assertTrinoExceptionThrownBy(() -> getFutureValue(new CreateMaterializedViewTask(plannerContext, new AllowAllAccessControl(), parser, analyzerFactory, materializedViewPropertyManager)
                .execute(statement, queryStateMachine, ImmutableList.of(), WarningCollector.NOOP)))
                .hasErrorCode(INVALID_MATERIALIZED_VIEW_PROPERTY)
                .hasMessage("Catalog 'catalog' does not support materialized view property 'baz'");

        assertEquals(metadata.getCreateMaterializedViewCallCount(), 0);
    }

    @Test
    public void testCreateDenyPermission()
    {
        CreateMaterializedView statement = new CreateMaterializedView(
                Optional.empty(),
                QualifiedName.of("test_mv"),
                simpleQuery(selectList(new AllColumns()), table(QualifiedName.of("catalog", "schema", "mock_table"))),
                false,
                true,
                ImmutableList.of(),
                Optional.empty());
        TestingAccessControlManager accessControl = new TestingAccessControlManager(transactionManager, emptyEventListenerManager());
        accessControl.loadSystemAccessControl(AllowAllSystemAccessControl.NAME, ImmutableMap.of());
        accessControl.deny(privilege("test_mv", CREATE_MATERIALIZED_VIEW));

        StatementAnalyzerFactory statementAnalyzerFactory = createTestingStatementAnalyzerFactory(
                plannerContext,
                accessControl,
                new TablePropertyManager(),
                new AnalyzePropertyManager());
        AnalyzerFactory analyzerFactory = new AnalyzerFactory(statementAnalyzerFactory, new StatementRewrite(ImmutableSet.of()));
        assertThatThrownBy(() -> getFutureValue(new CreateMaterializedViewTask(plannerContext, accessControl, parser, analyzerFactory, materializedViewPropertyManager)
                .execute(statement, queryStateMachine, ImmutableList.of(), WarningCollector.NOOP)))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("Cannot create materialized view catalog.schema.test_mv");
    }

    private QueryStateMachine stateMachine(TransactionManager transactionManager, MetadataManager metadata, AccessControl accessControl)
    {
        return QueryStateMachine.begin(
                "test",
                Optional.empty(),
                testSession,
                URI.create("fake://uri"),
                new ResourceGroupId("test"),
                false,
                transactionManager,
                accessControl,
                directExecutor(),
                metadata,
                WarningCollector.NOOP,
                Optional.empty());
    }

    private static class MockMetadata
            extends AbstractMockMetadata
    {
        private final CatalogName catalogHandle;
        private final Map<SchemaTableName, MaterializedViewDefinition> materializedViews = new ConcurrentHashMap<>();

        public MockMetadata(CatalogName catalogHandle)
        {
            this.catalogHandle = requireNonNull(catalogHandle, "catalogHandle is null");
        }

        @Override
        public void createMaterializedView(Session session, QualifiedObjectName viewName, MaterializedViewDefinition definition, boolean replace, boolean ignoreExisting)
        {
            materializedViews.put(viewName.asSchemaTableName(), definition);
            if (!ignoreExisting) {
                throw new TrinoException(ALREADY_EXISTS, "Materialized view already exists");
            }
        }

        @Override
        public Optional<CatalogName> getCatalogHandle(Session session, String catalogName)
        {
            if (catalogHandle.getCatalogName().equals(catalogName)) {
                return Optional.of(catalogHandle);
            }
            return Optional.empty();
        }

        @Override
        public TableSchema getTableSchema(Session session, TableHandle tableHandle)
        {
            return new TableSchema(tableHandle.getCatalogName(), MOCK_TABLE.getTableSchema());
        }

        @Override
        public Optional<TableHandle> getTableHandle(Session session, QualifiedObjectName tableName)
        {
            if (tableName.asSchemaTableName().equals(MOCK_TABLE.getTable())) {
                return Optional.of(
                        new TableHandle(
                                new CatalogName(CATALOG_NAME),
                                new TestingTableHandle(tableName.asSchemaTableName()),
                                TestingConnectorTransactionHandle.INSTANCE,
                                Optional.empty()));
            }
            return Optional.empty();
        }

        @Override
        public Map<String, ColumnHandle> getColumnHandles(Session session, TableHandle tableHandle)
        {
            return MOCK_TABLE.getColumns().stream()
                    .collect(toImmutableMap(
                            ColumnMetadata::getName,
                            column -> new TestingColumnHandle(column.getName())));
        }

        @Override
        public TableMetadata getTableMetadata(Session session, TableHandle tableHandle)
        {
            if ((tableHandle.getConnectorHandle() instanceof TestingTableHandle)) {
                if (((TestingTableHandle) tableHandle.getConnectorHandle()).getTableName().equals(MOCK_TABLE.getTable())) {
                    return new TableMetadata(new CatalogName("catalog"), MOCK_TABLE);
                }
            }

            return super.getTableMetadata(session, tableHandle);
        }

        @Override
        public Optional<MaterializedViewDefinition> getMaterializedView(Session session, QualifiedObjectName viewName)
        {
            return Optional.ofNullable(materializedViews.get(viewName.asSchemaTableName()));
        }

        @Override
        public Optional<ViewDefinition> getView(Session session, QualifiedObjectName viewName)
        {
            return Optional.empty();
        }

        public int getCreateMaterializedViewCallCount()
        {
            return materializedViews.size();
        }
    }
}
