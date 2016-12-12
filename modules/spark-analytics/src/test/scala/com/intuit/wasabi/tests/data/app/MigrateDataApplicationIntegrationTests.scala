package com.intuit.wasabi.tests.data.app

import cats.data.Xor._
import com.datastax.driver.core.Session
import com.datastax.spark.connector.cql.{CassandraConnector, CassandraConnectorConf}
import com.google.inject.name.Names
import com.google.inject.{Guice, Key}
import com.holdenkarau.spark.testing.SharedSparkContext
import com.intuit.wasabi.data.app.MigrateDataApplication
import com.intuit.wasabi.data.conf.AppConfig
import com.intuit.wasabi.data.conf.guice.migratedata.MigrateDataApplicationDI
import com.intuit.wasabi.data.exception.WasabiError
import com.intuit.wasabi.data.repository.SparkDataStoreRepository
import com.intuit.wasabi.data.util.Constants._
import com.intuit.wasabi.data.util.Utility
import com.typesafe.config.ConfigFactory
import org.apache.spark.{Logging, SparkConf}
import org.apache.spark.sql.DataFrame
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.testng.TestNGSuite
import org.testng.Reporter
import org.testng.annotations.{AfterTest, BeforeTest, Test}

/**
  * Created by nbarge on 12/5/16.
  */

class MigrateDataApplicationIntegrationTests extends TestNGSuite with Logging  {
  var NUM_OF_RECORDS: Int = 0

  @BeforeTest
  def beforeAll() {
    //super.beforeAll()

    val appId="migrate-data"
    val jMap: java.util.Map[String,String] = new java.util.HashMap[String,String]()
    jMap.put("app_id", appId)
    jMap.put("master", "local")

    val setting = new AppConfig(Option.apply(ConfigFactory.parseMap(jMap)))
    val appConfig = setting.getConfigForApp(appId)

    val injector = Guice.createInjector(new MigrateDataApplicationDI(appConfig))
    val sRepository = injector.getInstance(Key.get(classOf[SparkDataStoreRepository], Names.named("SourceDataStoreRepository")))
    val mApp = injector.getInstance(classOf[MigrateDataApplication])

    sRepository.execDDL("CREATE KEYSPACE IF NOT EXISTS test_ks WITH replication = {'class': 'SimpleStrategy', 'replication_factor': '3'}  AND durable_writes = true")
    log.info("Keyspace is created...")

    sRepository.execDDL("DROP TABLE IF EXISTS test_ks.empty_user_assignment ")
    sRepository.execDDL("DROP TABLE IF EXISTS test_ks.empty_user_assignment_by_userid ")
    sRepository.execDDL("DROP TABLE IF EXISTS test_ks.user_assignment ")
    sRepository.execDDL("DROP TABLE IF EXISTS test_ks.user_assignment_by_userid ")
    sRepository.execDDL("DROP TABLE IF EXISTS test_ks.red_button_bucket_user_assignments")
    sRepository.execDDL("DROP TABLE IF EXISTS test_ks.user_assignments_without_context")
    sRepository.execDDL("DROP TABLE IF EXISTS test_ks.user_assignments_transformed")

    log.info("Tables are dropped...")

    val emptyUserAssignmentDDL = "create table test_ks.empty_user_assignment (experiment_id uuid, user_id varchar, context varchar, bucket_label varchar, created timestamp, PRIMARY KEY ((experiment_id), context, user_id));"
    val emptyUserAssignmentByUserIdDDL = "create table test_ks.empty_user_assignment_by_userid (experiment_id uuid, user_id varchar, context varchar, bucket_label varchar, created timestamp, PRIMARY KEY ((user_id), context, experiment_id));"
    val userAssignmentDDL = "create table test_ks.user_assignment (experiment_id uuid, user_id varchar, context varchar, bucket_label varchar, created timestamp, PRIMARY KEY ((experiment_id), context, user_id));"
    val userAssignmentByUserIdDDL = "create table test_ks.user_assignment_by_userid (experiment_id uuid, user_id varchar, context varchar, bucket_label varchar, created timestamp, PRIMARY KEY ((user_id), context, experiment_id));"
    val redButtonUserAssignmentDDL = "create table test_ks.red_button_bucket_user_assignments (experiment_id uuid, user_id varchar, context varchar, bucket_label varchar, created timestamp, PRIMARY KEY ((user_id), context, experiment_id));"
    val userAssignmentWithoutContextDDL = "create table test_ks.user_assignments_without_context (experiment_id uuid, user_id varchar, bucket_label varchar, created timestamp, PRIMARY KEY ((user_id), experiment_id));"
    val transformedUserAssignmentDDL = "create table test_ks.user_assignments_transformed (experiment_id uuid, user_id varchar, context varchar, bucket_label varchar, created timestamp, PRIMARY KEY ((user_id), context, experiment_id));"

    sRepository.execDDL(emptyUserAssignmentDDL)
    sRepository.execDDL(emptyUserAssignmentByUserIdDDL)
    sRepository.execDDL(userAssignmentDDL)
    sRepository.execDDL(userAssignmentByUserIdDDL)
    sRepository.execDDL(redButtonUserAssignmentDDL)
    sRepository.execDDL(userAssignmentWithoutContextDDL)
    sRepository.execDDL(transformedUserAssignmentDDL)
    log.info("Tables are created...")

    sRepository.execDDL(s"INSERT INTO test_ks.user_assignment (experiment_id,user_id,context,bucket_label,created) " +
      s"VALUES(ea830e07-baff-40f3-b322-7c6d8742df7f,'test_user_1','TEST','red_button_bucket',dateof(now()))")
    sRepository.execDDL(s"INSERT INTO test_ks.user_assignment (experiment_id,user_id,context,bucket_label,created) " +
      s"VALUES(ea830e07-baff-40f3-b322-7c6d8742df7f,'test_user_2','TEST','blue_button_bucket',dateof(now()))")
    sRepository.execDDL(s"INSERT INTO test_ks.user_assignment (experiment_id,user_id,context,bucket_label,created) " +
      s"VALUES(ea830e07-baff-40f3-b322-7c6d8742df7f,'test_user_3','TEST','red_button_bucket',dateof(now()))")
    sRepository.execDDL(s"INSERT INTO test_ks.user_assignment (experiment_id,user_id,context,bucket_label,created) " +
      s"VALUES(ea830e07-baff-40f3-b322-7c6d8742df5f,'test_user_1','TEST','red_button_bucket',dateof(now()))")
    sRepository.execDDL(s"INSERT INTO test_ks.user_assignment (experiment_id,user_id,context,bucket_label,created) " +
      s"VALUES(ea830e07-baff-40f3-b322-7c6d8742df5f,'test_user_2','TEST','blue_button_bucket',dateof(now()))")
    sRepository.execDDL(s"INSERT INTO test_ks.user_assignment (experiment_id,user_id,context,bucket_label,created) " +
      s"VALUES(ea830e07-baff-40f3-b322-7c6d8742df5f,'test_user_3','TEST','red_button_bucket',dateof(now()))")
    log.info("Test data for user_assignment table is generated...")
    NUM_OF_RECORDS=6

    mApp.stop()


  }

  @AfterTest
  def afterAll() {
    val appId="migrate-data"
    val jMap: java.util.Map[String,String] = new java.util.HashMap[String,String]()
    jMap.put("app_id", appId)
    jMap.put("master", "local")

    val setting = new AppConfig(Option.apply(ConfigFactory.parseMap(jMap)))
    val appConfig = setting.getConfigForApp(appId)

    val injector = Guice.createInjector(new MigrateDataApplicationDI(appConfig))
    val mApp = injector.getInstance(classOf[MigrateDataApplication])
    val sRepository = injector.getInstance(Key.get(classOf[SparkDataStoreRepository], Names.named("SourceDataStoreRepository")))

    sRepository.execDDL("DROP KEYSPACE IF EXISTS test_ks")
    log.info("Keyspace is dropped...")

    mApp.stop()
    //super.afterAll()
  }

  @Test
  def copyEmptyTable() {
    val appId="migrate-data"
    val jMap: java.util.Map[String,String] = new java.util.HashMap[String,String]()
    jMap.put("app_id", appId)
    jMap.put("master","local")
    jMap.put("migrate-data.migration.datastores.src.keyspace","test_ks")
    jMap.put("migrate-data.migration.datastores.dest.keyspace","test_ks")
    jMap.put("migrate-data.migration.CopyUserAssignmentTblProcessor.src.table","empty_user_assignment")
    jMap.put("migrate-data.migration.CopyUserAssignmentTblProcessor.dest.table","empty_user_assignment_by_userid")
    jMap.put("migrate-data.migration.CopyUserAssignmentTblProcessor.src.select_projection","user_id,context,experiment_id,bucket_label,created")

    val setting = new AppConfig(Option.apply(ConfigFactory.parseMap(jMap)))
    val appConfig = setting.getConfigForApp(appId)

    val injector = Guice.createInjector(new MigrateDataApplicationDI(appConfig))
    val mApp = injector.getInstance(classOf[MigrateDataApplication])

    var isSuccess = false
    mApp.init() match {
      case Left(err: WasabiError) => isSuccess = false
      case Right(succ: Unit) => isSuccess = true
    }
    assert(isSuccess, "Couldn't initialize application...")

    mApp.run() match {
      case Left(err: WasabiError) => isSuccess = false
      case Right(succ: Unit) => isSuccess = true
    }
    assert(isSuccess, "Run should finish successfully for empty table...")


    val sRepository = injector.getInstance(Key.get(classOf[SparkDataStoreRepository], Names.named("SourceDataStoreRepository")))
    val dRepository = injector.getInstance(Key.get(classOf[SparkDataStoreRepository], Names.named("DestinationDataStoreRepository")))

    var srcCount: Long = -1
    sRepository.read("select count(1) from empty_user_assignment") match {
      case Left(err: WasabiError) => isSuccess = false
      case Right(result: DataFrame) => srcCount = result.collectAsList().get(0).getLong(0)
    }

    var destCount: Long = -1
    sRepository.read("select count(1) from empty_user_assignment_by_userid") match {
      case Left(err: WasabiError) => isSuccess = false
      case Right(result: DataFrame) => destCount = result.collectAsList().get(0).getLong(0)
    }

    assert(srcCount==destCount && srcCount==0, "Both source & destination table should not have any data...")

    mApp.stop()
  }

  @Test
  def copyNonEmptyTable {
    val appId="migrate-data"
    val jMap: java.util.Map[String,String] = new java.util.HashMap[String,String]()
    jMap.put("app_id", appId)
    jMap.put("master","local")
    jMap.put("migrate-data.migration.datastores.src.keyspace","test_ks")
    jMap.put("migrate-data.migration.datastores.dest.keyspace","test_ks")
    jMap.put("migrate-data.migration.CopyUserAssignmentTblProcessor.src.table","user_assignment")
    jMap.put("migrate-data.migration.CopyUserAssignmentTblProcessor.dest.table","user_assignment_by_userid")
    jMap.put("migrate-data.migration.CopyUserAssignmentTblProcessor.src.select_projection","user_id,context,experiment_id,bucket_label,created")

    val setting = new AppConfig(Option.apply(ConfigFactory.parseMap(jMap)))
    val appConfig = setting.getConfigForApp(appId)

    val injector = Guice.createInjector(new MigrateDataApplicationDI(appConfig))
    val mApp = injector.getInstance(classOf[MigrateDataApplication])

    var isSuccess = false
    mApp.init() match {
      case Left(err: WasabiError) => isSuccess = false
      case Right(succ: Unit) => isSuccess = true
    }
    assert(isSuccess, "Couldn't initialize application...")

    mApp.run() match {
      case Left(err: WasabiError) => isSuccess = false
      case Right(succ: Unit) => isSuccess = true
    }
    assert(isSuccess, "Run should finish successfully for empty table...")


    val sRepository = injector.getInstance(Key.get(classOf[SparkDataStoreRepository], Names.named("SourceDataStoreRepository")))
    val dRepository = injector.getInstance(Key.get(classOf[SparkDataStoreRepository], Names.named("DestinationDataStoreRepository")))

    var srcCount: Long = -1
    sRepository.read("select count(1) from user_assignment") match {
      case Left(err: WasabiError) => isSuccess = false
      case Right(result: DataFrame) => srcCount = result.collectAsList().get(0).getLong(0)
    }

    var destCount: Long = -1
    sRepository.read("select count(1) from user_assignment_by_userid") match {
      case Left(err: WasabiError) => isSuccess = false
      case Right(result: DataFrame) => destCount = result.collectAsList().get(0).getLong(0)
    }

    assert(srcCount==destCount && srcCount==NUM_OF_RECORDS, s"Both source & destination table should have $NUM_OF_RECORDS records...")

    mApp.stop()
  }

  @Test
  def copySubsetOfData {
    val appId="migrate-data"
    val jMap: java.util.Map[String,String] = new java.util.HashMap[String,String]()
    jMap.put("app_id", appId)
    jMap.put("master","local")
    jMap.put("migrate-data.migration.datastores.src.keyspace","test_ks")
    jMap.put("migrate-data.migration.datastores.dest.keyspace","test_ks")
    jMap.put("migrate-data.migration.CopyUserAssignmentTblProcessor.src.table","user_assignment")
    jMap.put("migrate-data.migration.CopyUserAssignmentTblProcessor.src.where_clause","bucket_label='red_button_bucket'")
    jMap.put("migrate-data.migration.CopyUserAssignmentTblProcessor.src.select_projection","user_id,context,experiment_id,bucket_label,created")
    jMap.put("migrate-data.migration.CopyUserAssignmentTblProcessor.dest.table","red_button_bucket_user_assignments")

    val setting = new AppConfig(Option.apply(ConfigFactory.parseMap(jMap)))
    val appConfig = setting.getConfigForApp(appId)

    val injector = Guice.createInjector(new MigrateDataApplicationDI(appConfig))
    val mApp = injector.getInstance(classOf[MigrateDataApplication])

    var isSuccess = false
    mApp.init() match {
      case Left(err: WasabiError) => isSuccess = false
      case Right(succ: Unit) => isSuccess = true
    }
    assert(isSuccess, "Couldn't initialize application...")

    mApp.run() match {
      case Left(err: WasabiError) => isSuccess = false
      case Right(succ: Unit) => isSuccess = true
    }
    assert(isSuccess, "Run should finish successfully for empty table...")

    val sRepository = injector.getInstance(Key.get(classOf[SparkDataStoreRepository], Names.named("SourceDataStoreRepository")))
    val dRepository = injector.getInstance(Key.get(classOf[SparkDataStoreRepository], Names.named("DestinationDataStoreRepository")))

    var destCount: Long = -1
    sRepository.read("select count(1) from red_button_bucket_user_assignments") match {
      case Left(err: WasabiError) => isSuccess = false
      case Right(result: DataFrame) => destCount = result.collectAsList().get(0).getLong(0)
    }

    assert(destCount==4, s"Destination (red_button_bucket_user_assignments) table should have 4 records...")

    mApp.stop()
  }

  @Test
  def copySubsetOfCols {
    val appId="migrate-data"
    val jMap: java.util.Map[String,String] = new java.util.HashMap[String,String]()
    jMap.put("app_id", appId)
    jMap.put("master","local")
    jMap.put("migrate-data.migration.datastores.src.keyspace","test_ks")
    jMap.put("migrate-data.migration.datastores.dest.keyspace","test_ks")
    jMap.put("migrate-data.migration.CopyUserAssignmentTblProcessor.src.table","user_assignment")
    jMap.put("migrate-data.migration.CopyUserAssignmentTblProcessor.src.select_projection","user_id,experiment_id,bucket_label,created")
    jMap.put("migrate-data.migration.CopyUserAssignmentTblProcessor.dest.table","user_assignments_without_context")

    val setting = new AppConfig(Option.apply(ConfigFactory.parseMap(jMap)))
    val appConfig = setting.getConfigForApp(appId)

    val injector = Guice.createInjector(new MigrateDataApplicationDI(appConfig))
    val mApp = injector.getInstance(classOf[MigrateDataApplication])

    var isSuccess = false
    mApp.init() match {
      case Left(err: WasabiError) => isSuccess = false
      case Right(succ: Unit) => isSuccess = true
    }
    assert(isSuccess, "Couldn't initialize application...")

    mApp.run() match {
      case Left(err: WasabiError) => isSuccess = false
      case Right(succ: Unit) => isSuccess = true
    }
    assert(isSuccess, "Run should finish successfully for empty table...")

    val sRepository = injector.getInstance(Key.get(classOf[SparkDataStoreRepository], Names.named("SourceDataStoreRepository")))
    val dRepository = injector.getInstance(Key.get(classOf[SparkDataStoreRepository], Names.named("DestinationDataStoreRepository")))

    var srcCount: Long = -1
    sRepository.read("select count(1) from user_assignment") match {
      case Left(err: WasabiError) => isSuccess = false
      case Right(result: DataFrame) => srcCount = result.collectAsList().get(0).getLong(0)
    }

    var destCount: Long = -1
    sRepository.read("select count(1) from user_assignments_without_context") match {
      case Left(err: WasabiError) => isSuccess = false
      case Right(result: DataFrame) => destCount = result.collectAsList().get(0).getLong(0)
    }

    assert(srcCount==destCount && srcCount==NUM_OF_RECORDS, s"Both source & destination table should have $NUM_OF_RECORDS records...")
    mApp.stop()
  }

  @Test
  def copyTransformedData {
    val appId="migrate-data"
    val jMap: java.util.Map[String,String] = new java.util.HashMap[String,String]()
    jMap.put("app_id", appId)
    jMap.put("master","local")
    jMap.put("migrate-data.migration.datastores.src.keyspace","test_ks")
    jMap.put("migrate-data.migration.datastores.dest.keyspace","test_ks")
    jMap.put("migrate-data.migration.CopyUserAssignmentTblProcessor.src.table","user_assignment")
    jMap.put("migrate-data.migration.CopyUserAssignmentTblProcessor.src.select_projection","user_id,text_concat(context, '-', 'DEV') context,experiment_id,bucket_label,created")
    jMap.put("migrate-data.migration.CopyUserAssignmentTblProcessor.dest.table","user_assignments_transformed")

    val setting = new AppConfig(Option.apply(ConfigFactory.parseMap(jMap)))
    val appConfig = setting.getConfigForApp(appId)

    val injector = Guice.createInjector(new MigrateDataApplicationDI(appConfig))
    val mApp = injector.getInstance(classOf[MigrateDataApplication])

    var isSuccess = false
    mApp.init() match {
      case Left(err: WasabiError) => isSuccess = false
      case Right(succ: Unit) => isSuccess = true
    }
    assert(isSuccess, "Couldn't initialize application...")

    mApp.run() match {
      case Left(err: WasabiError) => isSuccess = false
      case Right(succ: Unit) => isSuccess = true
    }
    assert(isSuccess, "Run should finish successfully for empty table...")

    val sRepository = injector.getInstance(Key.get(classOf[SparkDataStoreRepository], Names.named("SourceDataStoreRepository")))
    val dRepository = injector.getInstance(Key.get(classOf[SparkDataStoreRepository], Names.named("DestinationDataStoreRepository")))

    var srcCount: Long = -1
    sRepository.read("select count(1) from user_assignment") match {
      case Left(err: WasabiError) => isSuccess = false
      case Right(result: DataFrame) => srcCount = result.collectAsList().get(0).getLong(0)
    }

    var destCount: Long = -1
    sRepository.read("select count(1) from user_assignments_transformed") match {
      case Left(err: WasabiError) => isSuccess = false
      case Right(result: DataFrame) => destCount = result.collectAsList().get(0).getLong(0)
    }

    assert(srcCount==destCount && srcCount==NUM_OF_RECORDS, s"Both source & destination table should have $NUM_OF_RECORDS records...")
    mApp.stop()
  }
}
