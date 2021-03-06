package fi.vm.sade.hakuperusteet

import javax.servlet.ServletContext

import fi.vm.sade.hakuperusteet.admin.{Synchronization, AdminServlet}
import fi.vm.sade.hakuperusteet.db.HakuperusteetDatabase
import fi.vm.sade.hakuperusteet.koodisto.Koodisto
import fi.vm.sade.hakuperusteet.rsa.RSASigner
import fi.vm.sade.hakuperusteet.tarjonta.Tarjonta
import fi.vm.sade.hakuperusteet.validation.{UserValidator, ApplicationObjectValidator}
import org.scalatra.LifeCycle

class ScalatraAdminBootstrap extends LifeCycle {
  val config = Configuration.props
  val database = HakuperusteetDatabase.init(config)
  val countries = Koodisto.initCountries(config)
  val languages = Koodisto.initLanguages(config)
  val educations = Koodisto.initBaseEducation(config)
  val tarjonta = Tarjonta.init(config)
  val signer = RSASigner.init(config)
  val applicationObjectValidator = ApplicationObjectValidator(countries, educations)
  val userValidator = UserValidator(countries, languages)
  Synchronization(config, database, tarjonta, countries, signer)

  override def init(context: ServletContext) {
    context mount(new TarjontaServlet(tarjonta), "/api/v1/tarjonta")
    context mount(new PropertiesServlet(config, countries, languages, educations), "/api/v1/properties")
    context mount(new AdminServlet("/webapp-admin/index.html",config, userValidator, applicationObjectValidator, database), "/")
  }

}
