package fi.vm.sade.hakuperusteet.domain

case class SessionData(session: Session, user: Option[User], education: List[ApplicationObject], payment: List[Payment])
