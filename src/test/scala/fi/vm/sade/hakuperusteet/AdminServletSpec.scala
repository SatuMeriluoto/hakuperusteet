package fi.vm.sade.hakuperusteet

import fi.vm.sade.hakuperusteet.admin.AdminServlet
import fi.vm.sade.hakuperusteet.validation.{ApplicationObjectValidator, UserValidator}
import org.scalatest.FunSuite
import org.scalatra.test.scalatest.ScalatraSuite

import scala.io.BufferedSource


class AdminServletSpec extends FunSuite with ScalatraSuite with ServletTestDependencies {
  val stream = getClass.getResourceAsStream("/logoutRequest.xml")
  val logoutRequest = scala.io.Source.fromInputStream( stream ).mkString

  val s = new AdminServlet("/webapp-admin/index.html",config, UserValidator(countries,languages), ApplicationObjectValidator(countries,educations), database)
  addServlet(s, "/*")

  test("CAS logout") {
    post("/", Map("logoutRequest"->logoutRequest)) {
      status should equal (200)
    }
  }

}
