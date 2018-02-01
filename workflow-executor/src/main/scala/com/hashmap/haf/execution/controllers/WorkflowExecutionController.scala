package com.hashmap.haf.execution.controllers

import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.annotation.PostConstruct

import com.fasterxml.jackson.databind.ObjectMapper
import com.github.dexecutor.core.{DefaultDexecutor, DexecutorConfig, Duration, ExecutionConfig}
import com.hashmap.haf.execution.clients.{FunctionsServiceClient, WorkflowServiceClient}
import com.hashmap.haf.execution.executor.IgniteDexecutorState
import com.hashmap.haf.functions.compiler.FunctionCompiler
import com.hashmap.haf.functions.processors.VelocitySourceGenerator
import com.hashmap.haf.models.IgniteFunctionType
import com.hashmap.haf.workflow.constants.XmlConstants.{LIVY_TASK, SPARK_TASK, _}
import com.hashmap.haf.workflow.execution.IgniteSparkExecutionEngine
import com.hashmap.haf.workflow.factory.Factory.{TaskFactory, WorkflowTask}
import com.hashmap.haf.workflow.models.{DefaultWorkflow, Workflow}
import com.hashmap.haf.workflow.task.{DefaultTaskProvider, SparkIgniteTask}
import org.apache.ignite.internal.IgnitionEx
import org.apache.ignite.{Ignite, Ignition}
import org.springframework.beans.factory.annotation.{Autowired, Value}
import org.springframework.web.bind.annotation._

import scala.xml.{Node, NodeSeq}

@RestController
@RequestMapping(Array("/api"))
class WorkflowExecutionController @Autowired()(functionsServiceClient: FunctionsServiceClient,
                                               workflowServiceClient: WorkflowServiceClient,
                                               sourceGenerator: VelocitySourceGenerator,
                                               functionCompiler: FunctionCompiler) {
  @Value("${functions.ignite.config}")
  var igniteConfigPath: String = _

  var ignite: Ignite = _

  @PostConstruct
  def init(): Unit = {
    val igConfig = Thread.currentThread().getContextClassLoader.getResource(igniteConfigPath)
    val configuration = IgnitionEx.loadConfiguration(igConfig).get1()
    configuration.setClientMode(true)
    ignite = Ignition.start(configuration)
  }


  @RequestMapping(value = Array("/workflow/execute/{workflowId}"), method = Array(RequestMethod.GET))
  @ResponseBody
  def executeById(@PathVariable("workflowId") workflowId: String): String = {

    object CustomTaskFactory extends TaskFactory[UUID, String] {
      def create(xml: Node): WorkflowTask[UUID, String] = {
        (xml \ "_").headOption.map(_.label) match {
          case Some(SPARK_TASK) | Some(LIVY_TASK) => {
            val functionClassName = (xml \ CLASSNAME_ATTRIBUTE).text
            functionCompiler.loadClazz(functionClassName) match {
              case Some(c) => {
                c.getConstructor(classOf[NodeSeq], classOf[Ignite]).newInstance(xml, ignite).asInstanceOf[SparkIgniteTask]
              }
              case _ => {
                generateSourceAndCompile(functionClassName).get.getConstructor(classOf[NodeSeq], classOf[Ignite]).newInstance(xml, ignite).asInstanceOf[SparkIgniteTask]
              }
            }
          }
          case None => throw new IllegalStateException("At least one tak should be defined in a workflow")
          case _ => throw new IllegalArgumentException("No factory method found for given task")
        }
      }
    }

    val workflowXml: String = workflowServiceClient.getFunction(workflowId)
    val workflow = DefaultWorkflow(workflowXml, CustomTaskFactory)
    val executor: DefaultDexecutor[UUID, String] = newTaskExecutor(workflow)
    workflow.buildTaskGraph(executor)
    executor.execute(new ExecutionConfig().scheduledRetrying(3, new Duration(2, TimeUnit.SECONDS)))
    "Done"
  }

  private def generateSourceAndCompile(functionClassName: String) = {
    val functionTypeString = functionsServiceClient.getFunction(functionClassName.substring(functionClassName.lastIndexOf(".")+1))
    val mapper = new ObjectMapper()
    val functionType = mapper.readValue(functionTypeString, classOf[IgniteFunctionType])
    sourceGenerator.generateSource(functionType) match {
      case Right(source) => {
        functionCompiler.compile(functionClassName, source)
        functionCompiler.loadClazz(functionClassName)
      }
      case Left(_) => None
    }
  }

  private def newTaskExecutor(workflow: Workflow[UUID, String]) = {
    val executorState = new IgniteDexecutorState[UUID, String](workflow.getId.toString, ignite)
    val config = new DexecutorConfig[UUID, String](new IgniteSparkExecutionEngine[UUID, String](executorState), DefaultTaskProvider(workflow))
    config.setDexecutorState(executorState)
    new DefaultDexecutor[UUID, String](config)
  }
}
