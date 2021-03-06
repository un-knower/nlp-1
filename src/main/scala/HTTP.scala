import java.util.concurrent.Executors

import org.http4s.MediaType.{`application/pdf`, `text/html`, `text/xml`}
import org.http4s.{MediaType, _}
import org.http4s.dsl._
import org.http4s.headers.`Content-Type`
import org.http4s.server.blaze._
import org.http4s.server.{Server, ServerApp}
import ruc.nlp._

import scala.concurrent.duration._
import scala.xml.XML
import scalaz.concurrent.Task


object MyService {
  val service = HttpService {
    case _ => Ok(Task {
      println("hello world!")
      "hello world!"
    })
  }
}

object ExtractUrlQueryParamMatcher extends QueryParamDecoderMatcher[String]("url")
object ExtractRefQueryParamMatcher extends QueryParamDecoderMatcher[String]("ref")

object ExtractService {
  implicit val scheduledThreadPool = Executors.newScheduledThreadPool(5)

  /**
    * 执行挖掘任务，要求输入的文档内容格式为：
    * <article>
    * <title> 三星Note7“炸机门”仍在发酵</title>
    * <content><![CDATA[ 9月24日下午有网友爆料称，从苏宁购买的国行三星Note7发生了爆炸事故...  ]]></content>
    * </article>
    */
  def miningArticleTask(body: String): Task[String] = Task {
    val doc = XML.loadString(body)
    val title = (doc \\ "title").text
    val content = (doc \\ "content").text

    {
      <result>
        <keyword>
          {WebExtractor.extractKeywords(title, content)}
        </keyword>
        <finger>
          {WebExtractor.fingerprint(title, content)}
        </finger>
        <sentiment>
          {WebExtractor.sentiment(title, content)}
        </sentiment>
      </result>
    }.toString
  }.timed(2 second).handleWith {
    case e: java.util.concurrent.TimeoutException => Task.now((<error>
      {e}
    </error>) toString)
    case e: Throwable =>
      Task.now({
        <error>
          <message>
            {e}
          </message>
          <received>
            {body}
          </received>
        </error>
      }.toString)
  }


  def extractArticleByContentTask(url: String, content: String): Task[String] = Task {
    println(s"fetching $url by provided content...")
    val article = WebExtractor.extractArticle(url, content)
    article.toXML
  }.timed(3 second).handleWith {
    case e: java.util.concurrent.TimeoutException => Task.now((<error>
      {e}
    </error>) toString)
    case e: Throwable =>
      Task.now({
        <error>
          <message>
            {e}
          </message>
          <url>
            {url}
          </url>
          <content>
            {content}
          </content>
        </error>
      }.toString)
  }

  def extractArticleByUrlTask(url: String): Task[String] = Task {
    println("fetching " + url)
    val article = WebExtractor.extractArticle(url)
    article.toXML
  }.timed(5 second).handleWith {
    case e: java.util.concurrent.TimeoutException => Task.now((<error>
      {e}
    </error>) toString)
    case e: Throwable => Task.now((<error>
      {e}
    </error>) toString)
  }

  val service = HttpService {
    case req@GET -> Root =>
      Ok(Task {"""<p>接口：/api/extract?url=xxx</p>"""})
        .withContentType(Some(`Content-Type`(`text/html`)))

    case request@POST -> Root / "extract" => {
      //val inputStream = scalaz.stream.io.toInputStream(request.body)
      val body: String = EntityDecoder.decodeString(request).run //获取传递的内容
      //按行分割
      val lines = body.split("\n").toList
      lines match {
        //如果以http开始
        case x :: xs if x.toLowerCase.startsWith("http") => Ok(extractArticleByContentTask(x, xs.mkString("\n")).run)

        case _ => Ok(Task.now({
          <error>
            <message>"""说明：POST的文本内容格式，第一行为URL地址，后面为该URL对应的网页源代码，例如：
              http://www.test.com/article01.html

              <html>
                html content...
              </html>
              """
            </message>
            <received>
              {body}
            </received>
          </error>
        } toString))
      }
    }
    case request@GET -> Root / "extract" :? ExtractUrlQueryParamMatcher(url) =>
      Ok(extractArticleByUrlTask(url).run)
        .putHeaders(`Content-Type`(`text/xml`))

    case request@GET -> Root / "aspdf" :? ExtractUrlQueryParamMatcher(url) =>
      Ok{
        //把抽取结果以pdf方式输出，需要事先安装wkhtmltopdf： http://wkhtmltopdf.org/
        import io.github.cloudify.scala.spdf._
        val pdf = Pdf(new PdfConfig {
          //orientation := Landscape
          orientation := Portrait
          pageSize := "Letter"
          marginTop := "1in"
          marginBottom := "1in"
          marginLeft := "1in"
          marginRight := "1in"
        })

        val html = Task{
          val article = WebExtractor.extractArticle(url)

          s"""<html>
              <head><title>${article getTitle}</title></head>
              <body>
                <center><h1 style="font-size:24px;font-family: Microsoft Yahei;">${article getTitle}</h1></center>
                <p style="color:gray;">${article.getKeywords}</p>
                <span style="font-size:16px;line-height:1.6;">
                ${article getFormattedContent}
                <span>
              </body>
          </html>"""
        }.run
        println("HTML:" + html)
        val outputStream = new java.io.ByteArrayOutputStream
        pdf.run(html, outputStream)
        outputStream.close()
        outputStream.toByteArray
      }.putHeaders(`Content-Type`(`application/pdf`))

    //根据传入的XML格式的文章标题和正文，进行关键词提取/指纹处理等任务。
    case request@POST -> Root / "mining" =>
      val body: String = EntityDecoder.decodeString(request).run //获取传递的内容
      Ok(miningArticleTask(body).run)
        .putHeaders(`Content-Type`(`text/xml`))

    case request@GET -> Root / "fetch" :? ExtractUrlQueryParamMatcher(url)  +& ExtractRefQueryParamMatcher(refer) =>
      var mediaType = `Content-Type`(`text/html`)
      Ok{
        val (contentType, content) = WebExtractor.fetch(url, refer)
        mediaType = `Content-Type`.parse(contentType).getOrElse(`Content-Type`(`text/html`))
        content
      }.timed(3 seconds).putHeaders(mediaType)

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
