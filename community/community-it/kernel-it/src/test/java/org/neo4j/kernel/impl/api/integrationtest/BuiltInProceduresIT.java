/*
 * Copyright (c) 2002-2019 "Neo4j,"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.kernel.impl.api.integrationtest;

import org.hamcrest.Description;
import org.hamcrest.Matcher;
import org.hamcrest.TypeSafeMatcher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;

import org.neo4j.collection.RawIterator;
import org.neo4j.common.DependencyResolver;
import org.neo4j.configuration.GraphDatabaseSettings;
import org.neo4j.cypher.internal.StringCacheMonitor;
import org.neo4j.graphdb.Resource;
import org.neo4j.internal.helpers.collection.MapUtil;
import org.neo4j.internal.helpers.collection.Pair;
import org.neo4j.internal.kernel.api.SchemaWrite;
import org.neo4j.internal.kernel.api.TokenWrite;
import org.neo4j.internal.kernel.api.Transaction;
import org.neo4j.internal.kernel.api.exceptions.ProcedureException;
import org.neo4j.internal.kernel.api.procs.ProcedureHandle;
import org.neo4j.internal.kernel.api.security.LoginContext;
import org.neo4j.internal.schema.IndexDescriptor;
import org.neo4j.internal.schema.LabelSchemaDescriptor;
import org.neo4j.internal.schema.SchemaDescriptor;
import org.neo4j.kernel.api.index.IndexProvider;
import org.neo4j.kernel.api.security.AnonymousContext;
import org.neo4j.kernel.impl.api.index.IndexProviderMap;
import org.neo4j.kernel.impl.api.index.IndexingService;
import org.neo4j.kernel.impl.api.index.sampling.IndexSamplingMode;
import org.neo4j.kernel.impl.util.ValueUtils;
import org.neo4j.kernel.internal.Version;
import org.neo4j.monitoring.Monitors;
import org.neo4j.values.AnyValue;
import org.neo4j.values.storable.TextValue;
import org.neo4j.values.virtual.MapValue;
import org.neo4j.values.virtual.VirtualValues;

import static java.util.concurrent.TimeUnit.MINUTES;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.arrayContaining;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.startsWith;
import static org.hamcrest.core.IsEqual.equalTo;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.neo4j.internal.helpers.collection.Iterators.asList;
import static org.neo4j.internal.kernel.api.procs.ProcedureSignature.procedureName;
import static org.neo4j.internal.kernel.api.security.LoginContext.AUTH_DISABLED;
import static org.neo4j.internal.schema.SchemaDescriptor.forLabel;
import static org.neo4j.kernel.api.ResourceManager.EMPTY_RESOURCE_MANAGER;
import static org.neo4j.values.storable.Values.EMPTY_STRING;
import static org.neo4j.values.storable.Values.doubleValue;
import static org.neo4j.values.storable.Values.longValue;
import static org.neo4j.values.storable.Values.stringValue;

class BuiltInProceduresIT extends KernelIntegrationTest
{
    @Test
    void listAllLabels() throws Throwable
    {
        // Given
        Transaction transaction = newTransaction( AnonymousContext.writeToken() );
        long nodeId = transaction.dataWrite().nodeCreate();
        int labelId = transaction.tokenWrite().labelGetOrCreateForName( "MyLabel" );
        transaction.dataWrite().nodeAddLabel( nodeId, labelId );
        commit();

        // When
        RawIterator<AnyValue[],ProcedureException> stream =
                procs().procedureCallRead( procs().procedureGet( procedureName( "db", "labels" ) ).id(), new AnyValue[0] );

        // Then
        assertThat( asList( stream ), contains( equalTo( new AnyValue[]{stringValue("MyLabel"), longValue(1L)} ) ) );
    }

    @Test
    @Timeout( value = 6, unit = MINUTES )
    void listAllLabelsMustNotBlockOnConstraintCreatingTransaction() throws Throwable
    {
        // Given
        Transaction transaction = newTransaction( AnonymousContext.writeToken() );
        long nodeId = transaction.dataWrite().nodeCreate();
        int labelId = transaction.tokenWrite().labelGetOrCreateForName( "MyLabel" );
        int propKey = transaction.tokenWrite().propertyKeyCreateForName( "prop", false );
        transaction.dataWrite().nodeAddLabel( nodeId, labelId );
        commit();

        CountDownLatch constraintLatch = new CountDownLatch( 1 );
        CountDownLatch commitLatch = new CountDownLatch( 1 );
        FutureTask<Void> createConstraintTask = new FutureTask<>( () ->
        {
            SchemaWrite schemaWrite = schemaWriteInNewTransaction();
            try ( Resource ignore = captureTransaction() )
            {
                schemaWrite.uniquePropertyConstraintCreate( SchemaDescriptor.forLabel( labelId, propKey ) );
                // We now hold a schema lock on the "MyLabel" label. Let the procedure calling transaction have a go.
                constraintLatch.countDown();
                commitLatch.await();
            }
            rollback();
            return null;
        } );
        Thread constraintCreator = new Thread( createConstraintTask );
        constraintCreator.start();

        // When
        constraintLatch.await();
        RawIterator<AnyValue[],ProcedureException> stream =
                procs().procedureCallRead( procs().procedureGet( procedureName( "db", "labels" ) ).id(), new AnyValue[0] );

        // Then
        try
        {
            assertThat( asList( stream ), contains( equalTo( new AnyValue[]{stringValue( "MyLabel" ), longValue( 1 )} ) ) );
        }
        finally
        {
            commitLatch.countDown();
        }
        createConstraintTask.get();
        constraintCreator.join();
    }

    @Test
    void listPropertyKeys() throws Throwable
    {
        // Given
        TokenWrite ops = tokenWriteInNewTransaction();
        ops.propertyKeyGetOrCreateForName( "MyProp" );
        commit();

        // When
        RawIterator<AnyValue[],ProcedureException> stream =
                procs().procedureCallRead( procs().procedureGet( procedureName( "db", "propertyKeys" ) ).id(), new AnyValue[0] );

        // Then
        assertThat( asList( stream ), contains( equalTo( new AnyValue[]{stringValue( "MyProp" )} ) ) );
    }

    @Test
    void listRelationshipTypes() throws Throwable
    {
        // Given
        Transaction transaction = newTransaction( AnonymousContext.writeToken() );
        int relType = transaction.tokenWrite().relationshipTypeGetOrCreateForName( "MyRelType" );
        long startNodeId = transaction.dataWrite().nodeCreate();
        long endNodeId = transaction.dataWrite().nodeCreate();
        transaction.dataWrite().relationshipCreate( startNodeId, relType, endNodeId );
        commit();

        // When
        RawIterator<AnyValue[],ProcedureException> stream =
                procs().procedureCallRead( procs().procedureGet( procedureName( "db", "relationshipTypes" ) ).id(), new AnyValue[0] );

        // Then
        assertThat( asList( stream ), contains( equalTo( new AnyValue[]{stringValue( "MyRelType" ), longValue( 1L ) } ) ) );
    }

    @Test
    void listProcedures() throws Throwable
    {
        // When
        ProcedureHandle procedures = procs().procedureGet( procedureName( "dbms", "procedures" ) );
        RawIterator<AnyValue[],ProcedureException> stream = procs().procedureCallRead( procedures.id(), new AnyValue[0] );

        // Then
        assertThat( asList( stream ), containsInAnyOrder(
                proc( "dbms.listConfig", "(searchString =  :: STRING?) :: (name :: STRING?, description :: STRING?, value :: STRING?, dynamic :: BOOLEAN?)",
                        "List the currently active config of Neo4j.", "DBMS" ),
                proc( "db.constraints", "() :: (description :: STRING?)", "List all constraints in the database.", "READ" ),
                proc( "db.indexes", "() :: (description :: STRING?, indexName :: STRING?, tokenNames :: LIST? OF STRING?, properties :: " +
                                "LIST? OF STRING?, state :: STRING?, type :: STRING?, progress :: FLOAT?, provider :: MAP?, id :: INTEGER?, " +
                                "failureMessage :: STRING?)",
                        "List all indexes in the database.", "READ" ),
                proc( "db.awaitIndex", "(index :: STRING?, timeOutSeconds = 300 :: INTEGER?) :: VOID",
                        "Wait for an index to come online (for example: CALL db.awaitIndex(\":Person(name)\")).", "READ" ),
                proc( "db.awaitIndexes", "(timeOutSeconds = 300 :: INTEGER?) :: VOID",
                        "Wait for all indexes to come online (for example: CALL db.awaitIndexes(\"500\")).", "READ" ),
                proc( "db.resampleIndex", "(index :: STRING?) :: VOID",
                        "Schedule resampling of an index (for example: CALL db.resampleIndex(\":Person(name)\")).", "READ" ),
                proc( "db.resampleOutdatedIndexes", "() :: VOID", "Schedule resampling of all outdated indexes.", "READ" ),
                proc( "db.propertyKeys", "() :: (propertyKey :: STRING?)", "List all property keys in the database.", "READ" ),
                proc( "db.labels", "() :: (label :: STRING?, nodeCount :: INTEGER?)", "List all labels in the database and their total count.", "READ" ),
                proc( "db.schema.visualization","() :: (nodes :: LIST? OF NODE?, relationships :: LIST? OF RELATIONSHIP?)",
                        "Visualize the schema of the data.", "READ" ),
                proc( "db.schema.nodeTypeProperties",
                        "() :: (nodeType :: STRING?, nodeLabels :: LIST? OF STRING?, propertyName :: STRING?, " +
                                "propertyTypes :: LIST? OF STRING?, mandatory :: BOOLEAN?)",
                        "Show the derived property schema of the nodes in tabular form.", "READ" ),
                proc( "db.schema.relTypeProperties", "() :: (relType :: STRING?, " +
                                "propertyName :: STRING?, propertyTypes :: LIST? OF STRING?, mandatory :: BOOLEAN?)",
                        "Show the derived property schema of the relationships in tabular form.", "READ" ),
                proc( "db.relationshipTypes", "() :: (relationshipType :: STRING?, relationshipCount :: INTEGER?)",
                        "List all relationship types in the database and their total count.", "READ" ),
                proc( "dbms.procedures", "() :: (name :: STRING?, signature :: " + "STRING?, description :: STRING?, mode :: STRING?)",
                        "List all procedures in the DBMS.", "DBMS" ),
                proc( "dbms.functions", "() :: (name :: STRING?, signature :: " + "STRING?, description :: STRING?, aggregating :: BOOLEAN?)",
                        "List all functions in the DBMS.", "DBMS" ),
                proc( "dbms.components", "() :: (name :: STRING?, versions :: LIST? OF" + " STRING?, edition :: STRING?)",
                        "List DBMS components and their versions.", "DBMS" ),
                proc( "dbms.queryJmx", "(query :: STRING?) :: (name :: STRING?, " + "description :: STRING?, attributes :: MAP?)",
                        "Query JMX management data by domain and name." + " For instance, \"org.neo4j:*\"", "DBMS" ),
                proc( "db.createLabel", "(newLabel :: STRING?) :: VOID", "Create a label", "WRITE" ),
                proc( "db.createProperty", "(newProperty :: STRING?) :: VOID", "Create a Property", "WRITE" ),
                proc( "db.createRelationshipType", "(newRelationshipType :: STRING?) :: VOID", "Create a RelationshipType", "WRITE" ),
                proc( "dbms.clearQueryCaches", "() :: (value :: STRING?)", "Clears all query caches.", "DBMS" ),
                proc( "db.createIndex", "(index :: STRING?, providerName :: STRING?) :: (index :: STRING?, providerName :: STRING?, status :: STRING?)",
                        "Create a schema index with specified index provider (for example: CALL db.createIndex(\":Person(name)\", \"lucene+native-2.0\")) - " +
                                "YIELD index, providerName, status", "SCHEMA" ),
                proc( "db.createUniquePropertyConstraint", "(index :: STRING?, providerName :: STRING?) :: " +
                                "(index :: STRING?, providerName :: STRING?, status :: STRING?)",
                        "Create a unique property constraint with index backed by specified index provider " +
                                "(for example: CALL db.createUniquePropertyConstraint(\":Person(name)\", \"lucene+native-2.0\")) - " +
                                "YIELD index, providerName, status", "SCHEMA" ),
                proc( "db.index.fulltext.awaitEventuallyConsistentIndexRefresh", "() :: VOID",
                        "Wait for the updates from recently committed transactions to be applied to any eventually-consistent fulltext indexes.", "READ" ),
                proc( "db.index.fulltext.awaitIndex", "(index :: STRING?, timeOutSeconds = 300 :: INTEGER?) :: VOID",
                        "Similar to db.awaitIndex(index, timeout), except instead of an index pattern, the index is specified by name. " +
                                "The name can be quoted by backticks, if necessary.", "READ" ),
                proc( "db.index.fulltext.createNodeIndex", "(indexName :: STRING?, labels :: LIST? OF STRING?, propertyNames :: LIST? OF STRING?, " +
                        "config = {} :: MAP?) :: VOID", startsWith( "Create a node fulltext index for the given labels and properties." ), "SCHEMA" ),
                proc( "db.index.fulltext.createRelationshipIndex",
                        "(indexName :: STRING?, relationshipTypes :: LIST? OF STRING?, propertyNames :: LIST? OF STRING?, config = {} :: MAP?) :: VOID",
                        startsWith( "Create a relationship fulltext index for the given relationship types and properties." ), "SCHEMA" ),
                proc( "db.index.fulltext.drop", "(indexName :: STRING?) :: VOID", "Drop the specified index.", "SCHEMA" ),
                proc( "db.index.fulltext.listAvailableAnalyzers", "() :: (analyzer :: STRING?, description :: STRING?)",
                        "List the available analyzers that the fulltext indexes can be configured with.", "READ" ),
                proc( "db.index.fulltext.queryNodes", "(indexName :: STRING?, queryString :: STRING?) :: (node :: NODE?, score :: FLOAT?)",
                        "Query the given fulltext index. Returns the matching nodes and their lucene query score, ordered by score.", "READ"),
                proc( "db.index.fulltext.queryRelationships", "(indexName :: STRING?, queryString :: STRING?) :: (relationship :: RELATIONSHIP?, " +
                        "score :: FLOAT?)", "Query the given fulltext index. Returns the matching relationships and their lucene query score, ordered by " +
                        "score.", "READ" ),
                proc( "db.prepareForReplanning", "(timeOutSeconds = 300 :: INTEGER?) :: VOID",
                        "Triggers an index resample and waits for it to complete, and after that clears query caches." +
                        " After this procedure has finished queries will be planned using the latest database " +
                        "statistics.",
                        "READ" ),
                proc( "db.stats.retrieve", "(section :: STRING?, config = {} :: MAP?) :: (section :: STRING?, data :: MAP?)",
                      "Retrieve statistical data about the current database. Valid sections are 'GRAPH COUNTS', 'TOKENS', 'QUERIES', 'META'", "READ" ),
                proc( "db.stats.retrieveAllAnonymized", "(graphToken :: STRING?, config = {} :: MAP?) :: (section :: STRING?, data :: MAP?)",
                      "Retrieve all available statistical data about the current database, in an anonymized form.", "READ" ),
                proc( "db.stats.status", "() :: (section :: STRING?, status :: STRING?, data :: MAP?)",
                      "Retrieve the status of all available collector daemons, for this database.", "READ" ),
                proc( "db.stats.collect", "(section :: STRING?, config = {} :: MAP?) :: (section :: STRING?, success :: BOOLEAN?, message :: STRING?)",
                      "Start data collection of a given data section. Valid sections are 'QUERIES'", "READ" ),
                proc( "db.stats.stop", "(section :: STRING?) :: (section :: STRING?, success :: BOOLEAN?, message :: STRING?)",
                      "Stop data collection of a given data section. Valid sections are 'QUERIES'", "READ" ),
                proc( "db.stats.clear", "(section :: STRING?) :: (section :: STRING?, success :: BOOLEAN?, message :: STRING?)",
                        "Clear collected data of a given data section. Valid sections are 'QUERIES'", "READ" ),
                proc( "dbms.routing.getRoutingTable", "(context :: MAP?, database = null :: STRING?) :: (ttl :: INTEGER?, servers :: LIST? OF MAP?)",
                        "Returns endpoints of this instance.", "DBMS" ),
                proc( "dbms.cluster.routing.getRoutingTable", "(context :: MAP?, database = null :: STRING?) :: (ttl :: INTEGER?, servers :: LIST? OF MAP?)",
                        "Returns endpoints of this instance.", "DBMS" )
        ) );
        commit();
    }

    @Test
    void failWhenCallingNonExistingProcedures()
    {
        assertThrows( ProcedureException.class,
            () -> dbmsOperations().procedureCallDbms( -1, new AnyValue[0], dependencyResolver, AnonymousContext.none().authorize(
                LoginContext.IdLookup.EMPTY, GraphDatabaseSettings.DEFAULT_DATABASE_NAME ), EMPTY_RESOURCE_MANAGER, valueMapper ) );
    }

    @Test
    void listAllComponents() throws Throwable
    {
        // Given a running database

        // When
        RawIterator<AnyValue[],ProcedureException> stream =
                procs().procedureCallRead( procs().procedureGet( procedureName( "dbms", "components" ) ).id(), new AnyValue[0] );

        // Then
        assertThat( asList( stream ), contains( equalTo( new AnyValue[]{stringValue( "Neo4j Kernel" ),
                VirtualValues.list( stringValue( Version.getNeo4jVersion() ) ), stringValue( "community" )} ) ) );

        commit();
    }

    @Test
    void listAllIndexes() throws Throwable
    {
        // Given
        Transaction transaction = newTransaction( AUTH_DISABLED );
        int labelId1 = transaction.tokenWrite().labelGetOrCreateForName( "Person" );
        int labelId2 = transaction.tokenWrite().labelGetOrCreateForName( "Age" );
        int propertyKeyId1 = transaction.tokenWrite().propertyKeyGetOrCreateForName( "foo" );
        int propertyKeyId2 = transaction.tokenWrite().propertyKeyGetOrCreateForName( "bar" );
        LabelSchemaDescriptor personFooDescriptor = forLabel( labelId1, propertyKeyId1 );
        LabelSchemaDescriptor ageFooDescriptor = forLabel( labelId2, propertyKeyId1 );
        LabelSchemaDescriptor personFooBarDescriptor = forLabel( labelId1, propertyKeyId1, propertyKeyId2 );
        transaction.schemaWrite().indexCreate( personFooDescriptor );
        transaction.schemaWrite().uniquePropertyConstraintCreate( ageFooDescriptor );
        transaction.schemaWrite().indexCreate( personFooBarDescriptor );
        commit();

        //let indexes come online
        try ( org.neo4j.graphdb.Transaction tx = db.beginTx() )
        {
            db.schema().awaitIndexesOnline( 2, MINUTES );
            tx.success();
        }

        transaction = newTransaction();
        IndexDescriptor personFooIndex = transaction.schemaRead().index( personFooDescriptor );
        IndexDescriptor ageFooIndex = transaction.schemaRead().index( ageFooDescriptor );
        IndexDescriptor personFooBarIndex = transaction.schemaRead().index( personFooBarDescriptor );

        // When
        RawIterator<AnyValue[],ProcedureException> stream =
                procs().procedureCallRead( procs().procedureGet( procedureName( "db", "indexes" ) ).id(), new AnyValue[0] );

        Set<AnyValue[]> result = new HashSet<>();
        while ( stream.hasNext() )
        {
            result.add( stream.next() );
        }

        // Then
        IndexProviderMap indexProviderMap = db.getDependencyResolver().resolveDependency( IndexProviderMap.class );
        IndexingService indexingService = db.getDependencyResolver().resolveDependency( IndexingService.class );
        IndexProvider provider = indexProviderMap.getDefaultProvider();
        MapValue pdm = ValueUtils.asMapValue( MapUtil.map( // Provider Descriptor Map.
                "key", provider.getProviderDescriptor().getKey(), "version",
                provider.getProviderDescriptor().getVersion() ) );
        assertThat( result, containsInAnyOrder(
                new AnyValue[]{stringValue( "INDEX ON :Age(foo)" ), stringValue( ageFooIndex.getName() ),
                        VirtualValues.list( stringValue( "Age" ) ), VirtualValues.list( stringValue( "foo" ) ),
                        stringValue( "ONLINE" ),
                        stringValue( "node_unique_property" ), doubleValue( 100D ), pdm,
                        longValue( indexingService.getIndexId( ageFooDescriptor ) ),
                        EMPTY_STRING},
                new AnyValue[]{stringValue( "INDEX ON :Person(foo)" ), stringValue( personFooIndex.getName() ),
                        VirtualValues.list( stringValue( "Person" ) ),
                        VirtualValues.list( stringValue( "foo" ) ), stringValue( "ONLINE" ),
                        stringValue( "node_label_property" ), doubleValue( 100D ), pdm,
                        longValue( indexingService.getIndexId( personFooDescriptor ) ), EMPTY_STRING},
                new AnyValue[]{stringValue( "INDEX ON :Person(foo, bar)" ), stringValue( personFooBarIndex.getName() ),
                        VirtualValues.list( stringValue( "Person" ) ),
                        VirtualValues.list( stringValue( "foo" ), stringValue( "bar" ) ), stringValue( "ONLINE" ),
                        stringValue( "node_label_property" ), doubleValue( 100D ), pdm,
                        longValue( indexingService.getIndexId( personFooBarDescriptor ) ), EMPTY_STRING}
        ) );
        commit();
    }

    @Test
    @Timeout( value = 6, unit = MINUTES )
    void listAllIndexesMustNotBlockOnConstraintCreatingTransaction() throws Throwable
    {
        // Given
        Transaction transaction = newTransaction( AUTH_DISABLED );
        int labelId1 = transaction.tokenWrite().labelGetOrCreateForName( "Person" );
        int labelId2 = transaction.tokenWrite().labelGetOrCreateForName( "Age" );
        int propertyKeyId1 = transaction.tokenWrite().propertyKeyGetOrCreateForName( "foo" );
        int propertyKeyId2 = transaction.tokenWrite().propertyKeyGetOrCreateForName( "bar" );
        int propertyKeyId3 = transaction.tokenWrite().propertyKeyGetOrCreateForName( "baz" );
        LabelSchemaDescriptor personFooDescriptor = forLabel( labelId1, propertyKeyId1 );
        LabelSchemaDescriptor ageFooDescriptor = forLabel( labelId2, propertyKeyId1 );
        LabelSchemaDescriptor personFooBarDescriptor = forLabel( labelId1, propertyKeyId1, propertyKeyId2 );
        LabelSchemaDescriptor personBazDescriptor = forLabel( labelId1, propertyKeyId3 );
        transaction.schemaWrite().indexCreate( personFooDescriptor );
        transaction.schemaWrite().uniquePropertyConstraintCreate( ageFooDescriptor );
        transaction.schemaWrite().indexCreate( personFooBarDescriptor );
        commit();

        transaction = newTransaction();
        IndexDescriptor personFooIndex = transaction.schemaRead().index( personFooDescriptor );
        IndexDescriptor ageFooIndex = transaction.schemaRead().index( ageFooDescriptor );
        IndexDescriptor personFooBarIndex = transaction.schemaRead().index( personFooBarDescriptor );
        commit();

        //let indexes come online
        try ( org.neo4j.graphdb.Transaction tx = db.beginTx() )
        {
            db.schema().awaitIndexesOnline( 2, MINUTES );
            tx.success();
        }

        CountDownLatch constraintLatch = new CountDownLatch( 1 );
        CountDownLatch commitLatch = new CountDownLatch( 1 );
        FutureTask<Void> createConstraintTask = new FutureTask<>( () ->
        {
            SchemaWrite schemaWrite = schemaWriteInNewTransaction();
            try ( Resource ignore = captureTransaction() )
            {
                schemaWrite.uniquePropertyConstraintCreate( forLabel( labelId1, propertyKeyId3 ) );
                // We now hold a schema lock on the "MyLabel" label. Let the procedure calling transaction have a go.
                constraintLatch.countDown();
                commitLatch.await();
            }
            rollback();
            return null;
        } );
        Thread constraintCreator = new Thread( createConstraintTask );
        constraintCreator.start();

        // When
        constraintLatch.await();
        RawIterator<AnyValue[],ProcedureException> stream =
                procs().procedureCallRead( procs().procedureGet( procedureName( "db", "indexes" ) ).id(), new AnyValue[0] );

        Set<Object[]> result = new HashSet<>();
        while ( stream.hasNext() )
        {
            result.add( stream.next() );
        }

        // Then
        try
        {
            IndexProviderMap indexProviderMap = db.getDependencyResolver().resolveDependency( IndexProviderMap.class );
            IndexingService indexing = db.getDependencyResolver().resolveDependency( IndexingService.class );
            IndexProvider provider = indexProviderMap.getDefaultProvider();
            MapValue pdm = ValueUtils.asMapValue( MapUtil.map( // Provider Descriptor Map.
                    "key", provider.getProviderDescriptor().getKey(), "version",
                    provider.getProviderDescriptor().getVersion() ) );
            assertThat( result, containsInAnyOrder(
                    new AnyValue[]{stringValue( "INDEX ON :Age(foo)" ), stringValue( ageFooIndex.getName() ),
                            VirtualValues.list( stringValue( "Age" ) ), VirtualValues.list( stringValue( "foo" ) ),
                            stringValue( "ONLINE" ),
                            stringValue( "node_unique_property" ), doubleValue( 100D ), pdm,
                            longValue( indexing.getIndexId( ageFooDescriptor ) ), EMPTY_STRING},
                    new AnyValue[]{stringValue( "INDEX ON :Person(foo)" ), stringValue( personFooIndex.getName() ),
                            VirtualValues.list( stringValue( "Person" ) ),
                            VirtualValues.list( stringValue( "foo" ) ),
                            stringValue( "ONLINE" ),
                            stringValue( "node_label_property" ), doubleValue( 100D ), pdm,
                            longValue( indexing.getIndexId( personFooDescriptor ) ), EMPTY_STRING},
                    new AnyValue[]{stringValue( "INDEX ON :Person(foo, bar)" ), stringValue( personFooBarIndex.getName() ),
                            VirtualValues.list( stringValue( "Person" ) ),
                            VirtualValues.list( stringValue( "foo" ), stringValue( "bar" ) ),
                            stringValue( "ONLINE" ),
                            stringValue( "node_label_property" ), doubleValue( 100D ), pdm,
                            longValue( indexing.getIndexId( personFooBarDescriptor ) ), EMPTY_STRING},
                    new AnyValue[]{stringValue( "INDEX ON :Person(baz)" ), stringValue( "index_5" ),
                            VirtualValues.list( stringValue( "Person" ) ),
                            VirtualValues.list( stringValue( "baz" ) ),
                            stringValue( "POPULATING" ),
                            stringValue( "node_unique_property" ), doubleValue( 100D ), pdm,
                            longValue( indexing.getIndexId( personBazDescriptor ) ), EMPTY_STRING}
            ) );
            commit();
        }
        finally
        {
            commitLatch.countDown();
        }
        createConstraintTask.get();
        constraintCreator.join();
    }

    @Test
    void prepareForReplanningShouldEmptyQueryCache()
    {
        // Given, something is cached
        db.execute( "MATCH (n) RETURN n" );

        ReplanMonitor monitor = replanMonitor();

        // When
        db.execute( "CALL db.prepareForReplanning()" );

        // Then, the initial query and the procedure call should now have been cleared
        assertThat( monitor.numberOfFlushedItems(), equalTo( 2L ) );
    }

    @Test
    void prepareForReplanningShouldTriggerIndexesSampling()
    {
        // Given
        ReplanMonitor monitor = replanMonitor();

        // When
        db.execute( "CALL db.prepareForReplanning()" );

        // Then
        assertThat( monitor.samplingMode(), equalTo( IndexSamplingMode.TRIGGER_REBUILD_UPDATED ) );
    }

    private ReplanMonitor replanMonitor()
    {
        Monitors monitors =
                dependencyResolver.resolveDependency( Monitors.class, DependencyResolver.SelectionStrategy.FIRST );

        ReplanMonitor monitorListener = new ReplanMonitor();
        monitors.addMonitorListener( monitorListener );
        return monitorListener;
    }

    private static class ReplanMonitor extends IndexingService.MonitorAdapter implements StringCacheMonitor
    {
        private long numberOfFlushedItems = -1L;
        private IndexSamplingMode samplingMode;

        @Override
        public void cacheFlushDetected( long sizeBeforeFlush )
        {
            numberOfFlushedItems = sizeBeforeFlush;
        }

        @Override
        public void indexSamplingTriggered( IndexSamplingMode mode )
        {
            samplingMode = mode;
        }

        long numberOfFlushedItems()
        {
            return numberOfFlushedItems;
        }

        IndexSamplingMode samplingMode()
        {
            return samplingMode;
        }

        @Override
        public void cacheHit( Pair<String,scala.collection.immutable.Map<String,Class<?>>> key )
        {
        }

        @Override
        public void cacheMiss( Pair<String,scala.collection.immutable.Map<String,Class<?>>> key )
        {
        }

        @Override
        public void cacheDiscard( Pair<String,scala.collection.immutable.Map<String,Class<?>>> key, String userKey,
                int secondsSinceReplan )
        {
        }

        @Override
        public void cacheRecompile( Pair<String,scala.collection.immutable.Map<String,Class<?>>> key )
        {
        }
    }

    private Matcher<AnyValue[]> proc( String procName, String procSignature, String description, String mode )
    {
        return equalTo( new AnyValue[]{stringValue( procName ), stringValue( procName + procSignature ), stringValue( description ), stringValue( mode )} );
    }

    @SuppressWarnings( {"unchecked", "SameParameterValue"} )
    private Matcher<AnyValue[]> proc( String procName, String procSignature, Matcher<String> description, String mode )
    {
        Matcher<AnyValue> desc = new TypeSafeMatcher<>()
        {
            @Override
            public void describeTo( Description description )
            {
                description.appendText( "invalid description" );
            }

            @Override
            protected boolean matchesSafely( AnyValue item )
            {
                return item instanceof TextValue && description.matches( ((TextValue) item).stringValue() );
            }
        };

        Matcher<AnyValue>[] matchers =
                new Matcher[]{equalTo( stringValue( procName )), equalTo( stringValue( procName + procSignature  ) ), desc, equalTo( stringValue( mode ) )};
        return arrayContaining( matchers );
    }
}