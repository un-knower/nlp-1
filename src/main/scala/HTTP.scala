import org.http4s.MediaType.{`text/html`, `text/xml`}
import org.http4s._
import org.http4s.dsl._
import org.http4s.headers.`Content-Type`
import org.http4s.server.blaze._
import org.http4s.server.{Server, ServerApp}
import ruc.nlp._

import scalaz.concurrent.Task

object MyService {
  val service = HttpService {
    case _ => Ok(Task {
      println("hello world!")
      "hello world!"
    })
  }
}

object ExtractQueryParamMatcher extends QueryParamDecoderMatcher[String]("url")

object ExtractService {

  def extractArticleByUrlTask(url: String): Task[String] = Task {
    println("fetching " + url)
    val article = WebExtractor.extractArticle(url)
    article.toXML
  }.timed(5000).handleWith {
    case e: java.util.concurrent.TimeoutException => Task.now((<error>{e}</error>) toString)
    case e: Throwable => Task.now((<error>{e}</error>) toString)
  }

  def extractArticleByContentTask(url: String, content:String): Task[String] = Task {
    println(s"fetching $url by provided content...")
    val article = WebExtractor.extractArticle(url, content)
    article.toXML
  }.timed(4000).handleWith {
    case e: java.util.concurrent.TimeoutException => Task.now((<error>{e}</error>) toString)
    case e: Throwable =>
      Task.now({
        <error>
          <message>{e}</message>
          <url>{url}</url>
          <content>{content}</content>
        </error>
      }.toString)
  }

  val service = HttpService {
    case req@GET -> Root =>
      Ok(Task {"""<p>接口：/api/extract?url=xxx</p>"""})
        .withContentType(Some(`Content-Type`(`text/html`)))

    case request@POST -> Root / "extract" =>
      //val inputStream = scalaz.stream.io.toInputStream(request.body)
      val body:String = EntityDecoder.decodeString(request).run //获取传递的内容
      //按行分割
      val lines = body.split("\n").toList
      lines match {
        //如果以http开始
        case x::xs if x.toLowerCase.startsWith("http") => Ok(extractArticleByContentTask(x, xs.mkString("\n")).run)

        case x => Ok( Task.now({<error>
          <message>"""说明：POST的文本内容格式，第一行为URL地址，后面为该URL对应的网页源代码，例如：
          http://www.test.com/article01.html
          <html>
            html content...
          </html>
          """
          </message>
          <received>{x}</received>
        </error>} toString))
      }

    case request@GET -> Root / "extract" :? ExtractQueryParamMatcher(url) =>
      Ok(extractArticleByUrlTask(url).run)
        .putHeaders(`Content-Type`(`text/xml`))
    case _ => Ok(Task {
      "echo!"
    })
  }
}

object HTTP extends ServerApp {
  override def server(args: List[String]): Task[Server] = {
    case class Config(host: String = "localhost",
                      port: Int = 8080,
                      path: String = "/api")

    val parser = new scopt.OptionParser[Config]("HTTP API") {
      head("Web Article Extractor", "2.8")

      opt[String]('h', "host").action((x, c) =>
        c.copy(host = x)).text("listen address")

      opt[Int]('p', "port").action((x, c) =>
        c.copy(port = x)).text("listen port")

      opt[String]('a', "path").action((x, c) =>
        c.copy(path = x)).text("servlet context path, default is /api")


      help("help").text("prints this usage text")

      note("\n xiatian, xia@ruc.edu.cn.")
    }

    // parser.parse returns Option[C]
    val (host: String, port: Int, path: String) = parser.parse(args, Config()) match {
      case Some(config) =>
        println(s"Listen on address $config")
        (config.host, config.port, config.path)
      case None => println("Wrong parameters, use default settings.")
        ("localhost", 8080, "/api")
    }

    BlazeBuilder.bindHttp(port, host)
      .mountService(ExtractService.service, path)
      .start
  }
}
