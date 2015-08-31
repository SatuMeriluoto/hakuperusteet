package fi.vm.sade.hakuperusteet

import java.net.URLEncoder

import com.typesafe.scalalogging.slf4j.LazyLogging
import fi.vm.sade.hakuperusteet.CasClient.JSessionId
import org.http4s.Uri._
import org.http4s.headers.{`Set-Cookie`, `Content-Type`, Location}
import org.http4s.util.CaseInsensitiveString
import org.json4s.Formats
import scodec.bits.ByteVector
import org.http4s._
import org.http4s.client.Client
import org.http4s.headers.Location
import org.json4s.native.Serialization.write
import scala.util.matching.Regex
import scalaz.concurrent.{Future, Task}
import scalaz.stream._
import org.http4s.headers.{Location, `Set-Cookie`, `Content-Type`}
import org.http4s._
import org.http4s.client.Client
import scodec.bits.ByteVector
import Uri._
import org.json4s._
import org.json4s.native.JsonMethods._
import org.json4s.native.Serialization.{read, write}
import scalaz.\/._
import scalaz.concurrent.{Future, Task}
import scalaz.stream.{Process, async, channel, Channel}

class HenkiloClient(henkiloServerUrl: Uri, client: Client = org.http4s.client.blaze.defaultClient) extends LazyLogging {
  def this(henkiloServerUrl: String, client: Client) = this(new Task(
    Future.now(
      Uri.fromString(henkiloServerUrl).
        leftMap((fail: ParseFailure) => new IllegalArgumentException(fail.sanitized))
    )).run, client)

  def haeHenkilo(user: User): Task[User] = client.prepAs[User](req(user))(json4sOf[User]).
    handle {
    case e: ParseException =>
      println(s"parse error details: ${e.failure.details}")
      throw e
    case e =>
      println(s"error: $e")
      throw e
  }

  private def reqHeaders: Headers = Headers(ActingSystem("hakuperusteet.hakuperusteet.backend"))

  private def req(user: User) = Request(
    method = Method.POST,
    uri = resolve(henkiloServerUrl, Uri(path = "/authentication-service/resources/s2s/hakuperusteet")),
    headers = reqHeaders
  ).withBody(user)(json4sEncoderOf)

  def parseJson4s[A] (json:String)(implicit formats: Formats, mf: Manifest[A]) = scala.util.Try(read[A](json)).map(right).recover{
    case t =>
      logger.error("json decodign failed", t)
      left(ParseFailure("json decoding failed", t.getMessage))
  }.get

  def json4sEncoderOf[A <: AnyRef](implicit formats: Formats, mf: Manifest[A]): EntityEncoder[A] = EntityEncoder.stringEncoder(Charset.`UTF-8`).contramap[A](item => write[A](item))
  .withContentType(`Content-Type`(MediaType.`application/json`))

  def json4sOf[A](implicit formats: Formats, mf: Manifest[A]): EntityDecoder[A] = EntityDecoder.decodeBy[A](MediaType.`application/json`){(msg) =>
    DecodeResult(EntityDecoder.decodeString(msg)(Charset.`UTF-8`).map(parseJson4s[A]))
  }
}

object CasClient {
  type JSessionId = String
}

case class CasParams(service: String, username: String, password: String)

class CasAbleClient(casClient: CasClient, casParams: CasParams, client: Client = org.http4s.client.blaze.defaultClient) extends Client {

  private val paramSource: scalaz.stream.Process[Task, CasParams] = Process(casParams).toSource

  private val sessions = paramSource through casClient.casSessionChannel

  private val sessionRefreshProcess = paramSource through casClient.sessionRefreshChannel

  private val requestChannel = channel.lift[Task, Request, Response]((req: Request) => client.prepare(req)).contramap[(Request, JSessionId)]{
    case (req:Request, session: JSessionId) => req.withHeaders(headers.Cookie(Cookie("JSESSIONID", session)))
  }

  private def sessionExpired(resp: Response): Boolean =
    resp.status.code == org.http4s.Status.Found.code && resp.headers.get(Location).exists(_.value.contains("/cas/login"))

  override def shutdown(): Task[Unit] = client.shutdown()

  override def prepare(req: Request): Task[Response] = {
    def requestProcess(req:Request): scalaz.stream.Process[Task, Response] = scalaz.stream.Process(req).toSource zip(sessions) through requestChannel flatMap {
      case resp if sessionExpired(resp) => sessionRefreshProcess.drain ++ requestProcess(req)
      case resp => scalaz.stream.Process(resp).toSource
    }

    requestProcess(req).runLast.map(_.getOrElse(throw new Exception("FAILURE!!!!")))
  }

}

class CasClient(virkailijaLoadBalancerUrl: Uri, client: Client = org.http4s.client.blaze.defaultClient) {
  def this(casServer: String, client: Client) = this(new Task(
    Future.now(
      Uri.fromString(casServer).
        leftMap((fail: ParseFailure) => new IllegalArgumentException(fail.sanitized))
    )).run, client)

  def this(casServer: String) = this(new Task(
    Future.now(
      Uri.fromString(casServer).
        leftMap((fail: ParseFailure) => new IllegalArgumentException(fail.sanitized))
    )).run)

  private type TGTUrl = String
  private val headers: Headers = Headers(`Content-Type`(MediaType.`application/x-www-form-urlencoded`))

  private def tgtReq(params: CasParams) = Request(
    method = Method.POST,
    uri = resolve(virkailijaLoadBalancerUrl, Uri(path = "/cas/v1/tickets")),
    headers = headers,
    body = scalaz.stream.Process(ByteVector(s"username=${URLEncoder.encode(params.username, "UTF8")}&password=${URLEncoder.encode(params.password, "UTF8")}".getBytes)).toSource
  )

  private def getTgt(params: CasParams): Task[TGTUrl] = {
    client.prepare(tgtReq(params)).handle {
      case e =>
        println(s"get tgt failed")
        throw e
    }.map(response => {
      if (response.status == org.http4s.Status.Created)
        response.headers.get(Location).getOrElse(throw new scala.Exception("Location header not available")).value
      else
        throw new Exception(s"invalid TGT creation status: ${response.status.code}")
    })
  }

  private def getJSessionId(params: CasParams)(tgtUrl: TGTUrl): Task[JSessionId] = {
    val service = URLEncoder.encode(s"$virkailijaLoadBalancerUrl${params.service}/j_spring_cas_security_check", "UTF8")
    def stReq = Request(
      method = Method.POST,
      uri = Uri.fromString(tgtUrl).valueOr((fail) => throw new IllegalArgumentException(fail.sanitized)),
      headers = headers,
      body = scalaz.stream.Process(ByteVector(s"service=$service".getBytes)).toSource
    )

    import org.http4s.EntityDecoder._

    client.prepAs[String](stReq)(text).handle {
      case e =>
        println(s"get st failed: $e")
        throw e
    }.flatMap((st: String) => {
      val serviceUri = s"${params.service}/j_spring_cas_security_check"
      def jSessionReq = {
        Request(
          method = Method.GET,
          uri = resolve(virkailijaLoadBalancerUrl, Uri(path = serviceUri, query = Query.fromPairs("ticket" -> st)))
        )
      }
      client.prepare(jSessionReq).handle {
        case e =>
          println("get jsession failed")
          throw e
      }.map(resp => {
        if (resp.status.isSuccess) {
          resp.headers.collectFirst {
            case `Set-Cookie`(`Set-Cookie`(cookie)) if cookie.name == "JSESSIONID" => cookie.content
          }.getOrElse(throw new Exception("JSESSIONID not found"))
        } else throw new Exception(s"service returned non-ok status code: ${resp.status.code}: ${resp.body.toString}")
      })
    })
  }

  private def fetchCasSession(params: CasParams): Task[JSessionId] = getTgt(params).flatMap(getJSessionId(params))

  private def refreshSession(params: CasParams): Task[JSessionId] = fetchCasSession(params).flatMap((session) => s.compareAndSet {
    case None => Some(Map(params -> session))
    case Some(map) => Some(map.updated(params, session))
  }).map(_.get(params))

  private val s = async.signalOf(Map[CasParams, JSessionId]())

  private def getSession(params: CasParams): Task[Option[JSessionId]] = s.get.map(_.get(params))

  private def fetchSessionFromStore(params: CasParams): Task[JSessionId] = getSession(params).flatMap {
    case Some(session) => Task.now(session)
    case None => refreshSession(params)
  }

  def sessionRefreshChannel: Channel[Task, CasParams, JSessionId] = channel.lift[Task, CasParams, JSessionId] {
    (casParams) => refreshSession(casParams)
  }

  def casSessionChannel: Channel[Task, CasParams, JSessionId] = channel.lift(fetchSessionFromStore)
}
object ActingSystem extends PatternedHeader {
  val headerName = CaseInsensitiveString("Caller-Id")
  override type HeaderT = ActingSystem
  override val pattern: Regex = "([^.]+\\.[^.]+\\.[^.]+)".r

  override def headerForCaptureGroup(group: String): ActingSystem = ActingSystem(group)
}

case class ActingSystem(val id:String) extends Header.Parsed {
  import ActingSystem._

  assert(pattern.pattern.matcher(id).matches())

  override def key: HeaderKey = ActingSystem

  override def renderValue(writer: util.Writer): writer.type = writer << id
}
trait PatternedHeader extends HeaderKey.Singleton {

  val pattern: Regex

  val headerName: CaseInsensitiveString

  def headerForCaptureGroup(group:String):HeaderT

  override def matchHeader(header: Header): Option[HeaderT] = header match {
    case Header(`headerName`, pattern(id)) => Some(headerForCaptureGroup(id))
    case default => None
  }

  override def name: CaseInsensitiveString = headerName
}
