package fi.vm.sade.hakuperusteet

import com.typesafe.config.ConfigFactory
import org.eclipse.jetty.server._
import org.eclipse.jetty.servlet.DefaultServlet
import org.eclipse.jetty.util.ssl.SslContextFactory
import org.eclipse.jetty.webapp.WebAppContext
import org.scalatra.servlet.ScalatraListener
import org.slf4j.LoggerFactory

object HakuperusteetServer {
  val logger = LoggerFactory.getLogger(this.getClass)

  def main(args: Array[String]) {
    val portHttp = ConfigFactory.load().getInt("port.http")
    val portHttps = ConfigFactory.load().getInt("port.https")

    val server = new Server()
    server.setHandler(createContext)
    server.setConnectors(createConnectors(portHttp, portHttps, server))

    server.start
    server.join
    logger.info(s"Hakuperusteet-server started on ports $portHttp and $portHttps")
  }

  private def createConnectors(portHttp: Int, portHttps: Int, server: Server): Array[Connector] = {
    val httpConnector = new ServerConnector(server, new HttpConnectionFactory(new HttpConfiguration))
    httpConnector.setPort(portHttp)
    val httpsConnector = createSSLConnector(portHttps, server)
    val connectors: Array[Connector] = List(httpConnector, httpsConnector).toArray
    connectors
  }

  private def createContext: WebAppContext = {
    val context = new WebAppContext()
    context setContextPath ("/hakuperusteet/")
    context.setResourceBase(getClass.getClassLoader.getResource("webapp").toExternalForm)
    context.addEventListener(new ScalatraListener)
    context.addServlet(classOf[DefaultServlet], "/")
    context
  }

  private def createSSLConnector(port: Int, server: Server) = {
    val sslContextFactory = new SslContextFactory
    sslContextFactory.setKeyStoreType("jks")
    sslContextFactory.setKeyStorePath(this.getClass.getClassLoader.getResource("keystore").toExternalForm)
    sslContextFactory.setKeyStorePassword("keystore")
    sslContextFactory.setKeyManagerPassword("keystore")

    val httpsConfig = new HttpConfiguration
    httpsConfig.setSecurePort(port)
    httpsConfig.addCustomizer(new SecureRequestCustomizer)

    val https = new ServerConnector(server, new SslConnectionFactory(sslContextFactory, "http/1.1"), new HttpConnectionFactory(httpsConfig))
    https.setPort(port)
    https
  }
}