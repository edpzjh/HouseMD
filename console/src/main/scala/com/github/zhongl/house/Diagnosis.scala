/*
 * Copyright 2012 zhongl
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package com.github.zhongl.house

import java.lang.instrument._
import java.lang.System.{currentTimeMillis => now}
import java.io._
import java.util.Date
import com.beust.jcommander.{ParameterException, JCommander}
import scala.collection.JavaConversions._
import management.ManagementFactory
import collection.mutable.ListBuffer
import java.net.URL

/**
 * @author <a href="mailto:zhong.lunfu@gmail.com">zhongl<a>
 */
object Diagnosis {

  private[house] def probeWith(agentOptions: String, inst: Instrumentation) {
    val args = parse(agentOptions.split(" "))

    reportTo(args.output) {
      implicit stream: PrintStream =>

        stream.printf("#Diagnosis report\n> created at %tc\n\n", new Date)

        Section("Summary") {
          val runtime = ManagementFactory.getRuntimeMXBean

          val pairs = ListBuffer.empty[String]

          pairs += ("name = " + runtime.getName)
          pairs += ("arguments = " + runtime.getInputArguments)
          pairs += ("starTime = %tc" format new Date(runtime.getStartTime))
          pairs += ("upTime = %1$d hours %2$d minutes %3$d seconds" format (Utils.convertToTimestamp(runtime.getUptime): _*))

          pairs.toIterator
        }

        Section("Enviroment") {
          list(sys.env).toIterator
        }

        Section("Properties") {
          list(sys.props.toMap).toIterator
        }

        Section("Loaded classes: " + args.loaded) {
          inst.getAllLoadedClasses.map {
            c: Class[_] =>

              object &{
                def unapply(list: List[ClassLoader]):Option[(List[ClassLoader], ClassLoader)] =
                  if (list.isEmpty) None else Some(list.take(list.size - 1), list(list.size - 1))
              }

              def classLoaderHierarchiesOf(a:Any):String = a match {
                case c:Class[_] => classLoaderHierarchiesOf(c.getClassLoader)
                case cl:ClassLoader => classLoaderHierarchiesOf(cl :: cl.getParent :: Nil)
                case before & null  => before.mkString("[",",","]")
                case before & last  => classLoaderHierarchiesOf(before ::: last :: last.getParent :: Nil)
                case _ => "[]"
              }

              val name: String = c.getName
              val path: String = '/' + name.replace('.', '/') + ".class"
              val resource: URL = c.getResource(path)

              if (!name.matches(args.loaded) || resource == null) null
              else name + " -> " + resource + " @ " +classLoaderHierarchiesOf(c)
          }.filter(_ != null).toIterator
        }

        val methodRegexs = args.params.tail
        val transformer = new Transformer(inst, methodRegexs, args.agentJarPath)
        transformer.probe()
        Section("Traces: " + methodRegexs.mkString(" ")) {
          AdviceProxy.delegate = HaltAdviceProxy(Trace, args.timeout, args.maxProbeCount) {
            cause: Cause =>
              transformer.reset()
              stream.printf("\nDiagnosing ended by %s \n", cause)
          }
          Trace
        }
    }
  }

  def parse(args: Array[String]) = {
    val argsObject = new Args
    val commander = new JCommander()
    commander.addObject(argsObject)

    try {
      commander.parse(args: _*)
      if (argsObject.params.size() < 2) throw new ParameterException("Missing parameter")

      val _ :: methodRegexs = argsObject.params.toList
      methodRegexs.foreach((new RegexValidator).validate("", _))

      argsObject
    } catch {
      case e =>
        val sb = new java.lang.StringBuilder()
        sb.append(e.getClass.getSimpleName).append(": ").append(e.getMessage).append('\n')
        commander.usage(sb)
        throw new RuntimeException(sb.toString, e)
    }
  }

  private[this] def list(kv: Map[String, String]) = kv map {
    case (k, v) => k + " = " + v
  }

  private[this] def reportTo(path: String)(appending: PrintStream => Unit) {
    mkdirsFor(path)
    val stream = new PrintStream(new BufferedOutputStream(new FileOutputStream(path, true)))
    try {
      appending(stream)
    } catch {
      case e => e.printStackTrace(stream)
    } finally {
      stream.flush()
      stream.close()
    }
  }

  private[this] def mkdirsFor(path: String) = new File(path).getParentFile.mkdirs()

  def agentmain(agentOptions: String, inst: Instrumentation) {
    probeWith(agentOptions, inst)
  }

  def premain(agentOptions: String, inst: Instrumentation) {
    probeWith(agentOptions, inst)
  }
}
