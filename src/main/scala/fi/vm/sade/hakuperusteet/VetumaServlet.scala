package fi.vm.sade.hakuperusteet

import java.util.Date

import com.typesafe.config.Config
import fi.vm.sade.hakuperusteet.db.HakuperusteetDatabase
import fi.vm.sade.hakuperusteet.domain.PaymentStatus.PaymentStatus
import fi.vm.sade.hakuperusteet.domain.{User, PaymentStatus, Payment}
import fi.vm.sade.hakuperusteet.email.{ReceiptValues, EmailTemplate, EmailSender}
import fi.vm.sade.hakuperusteet.google.GoogleVerifier
import fi.vm.sade.hakuperusteet.oppijantunnistus.OppijanTunnistus
import fi.vm.sade.hakuperusteet.tarjonta.Tarjonta
import fi.vm.sade.hakuperusteet.util.AuditLog
import fi.vm.sade.hakuperusteet.vetuma.Vetuma
import org.json4s.native.Serialization._

import scala.util.{Failure, Success, Try}

class VetumaServlet(config: Config, db: HakuperusteetDatabase, oppijanTunnistus: OppijanTunnistus, verifier: GoogleVerifier, emailSender: EmailSender, tarjonta: Tarjonta) extends HakuperusteetServlet(config, db, oppijanTunnistus, verifier) {

  get("/openvetuma") {
    failUnlessAuthenticated
    createVetuma(None)
  }

  get("/openvetuma/:hakukohdeoid") {
    failUnlessAuthenticated
    createVetuma(params.get("hakukohdeoid"))
  }

  private def getHref = params.get("href").getOrElse(halt(409))

  private def createVetuma(hakukohdeOid: Option[String]) = {
    val userData = userDataFromSession
    val language = "en"
    val referenceNumber = referenceNumberFromPersonOid(userData.personOid.getOrElse(halt(500)))
    val orderNro = referenceNumber + db.nextOrderNumber()
    val paymCallId = "PCID" + orderNro
    val payment = Payment(None, userData.personOid.get, new Date(), referenceNumber, orderNro, paymCallId, PaymentStatus.started)
    val paymentWithId = db.upsertPayment(payment).getOrElse(halt(500))
    AuditLog.auditPayment(userData, paymentWithId)
    write(Map("url" -> config.getString("vetuma.host"), "params" -> Vetuma(config, paymentWithId, language, getHref, hakukohdeOid).toParams))
  }

  post("/return/ok") {
    val hash = "#/effect/VetumaResultOk"
    handleReturn(hash, params.get("href").getOrElse(halt(500)), params.get("ao"), PaymentStatus.ok)
  }

  post("/return/cancel") {
    val hash = "#/effect/VetumaResultCancel"
    handleReturn(hash, params.get("href").getOrElse(halt(500)), params.get("ao"), PaymentStatus.cancel)
  }

  post("/return/error") {
    val hash = "#/effect/VetumaResultError"
    handleReturn(hash, params.get("href").getOrElse(halt(500)), params.get("ao"), PaymentStatus.error)
  }

  def referenceNumberFromPersonOid(personOid: String) = personOid.split("\\.").toList.last

  private def handleReturn(hash: String, href: String, hakukohdeOid: Option[String], status: PaymentStatus) {
    val macParams = createMacParams
    val expectedMac = params.getOrElse("MAC", "")
    if (!Vetuma.verifyReturnMac(config.getString("vetuma.shared.secret"), macParams, expectedMac)) halt(409)

    val userData = userDataFromSession
    db.findPaymentByOrderNumber(userData, params.getOrElse("ORDNR", "")) match {
      case Some(p) =>
        val paymentOk = p.copy(status = status)
        db.upsertPayment(paymentOk)
        AuditLog.auditPayment(userData, paymentOk)
        if (status == PaymentStatus.ok) {
          sendReceipt(userData, hakukohdeOid, paymentOk)
        }
        halt(status = 303, headers = Map("Location" -> createUrl(href, hash, hakukohdeOid)))
      case None =>
        // todo: handle this
        halt(status = 303, headers = Map("Location" -> createUrl(href, hash, hakukohdeOid)))
    }
  }

  private def createUrl(href: String, hash: String, hakukohdeOid: Option[String]) = href + hakukohdeOid.map(ao => s"ao/$ao").getOrElse("") + hash

  private def createMacParams = {
    def p(name: String) = params.getOrElse(name, "")
    List(p("RCVID"), p("TIMESTMP"), p("SO"), p("LG"), p("RETURL"), p("CANURL"), p("ERRURL"), p("PAYID"), p("REF"), p("ORDNR"), p("PAID"), p("STATUS"))
  }

  private def sendReceipt(userData: User, hakukohdeOid: Option[String], payment: Payment) = {
    val name = hakukohdeOid.flatMap(fetchNameFromTarjonta).getOrElse("this")
    val p = ReceiptValues(userData.fullName, name, config.getString("vetuma.amount"), payment.reference)
    emailSender.send(userData.email, "Studyinfo: Your payment has been received", EmailTemplate.renderReceipt(p))
  }

  private def fetchNameFromTarjonta(hakukohdeOid: String) =
    Try { tarjonta.getApplicationObject(hakukohdeOid) } match {
      case Success(ao) => Some(ao.name)
      case Failure(f) =>  None
    }
}
