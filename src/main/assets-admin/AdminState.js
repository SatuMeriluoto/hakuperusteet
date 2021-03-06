import Bacon from 'baconjs'
import _ from 'lodash'

import {submitUserDataToServer} from './userdata/UserDataForm.js'
import {submitEducationDataToServer} from './education/EducationForm.js'
import {submitPaymentDataToServer} from './payment/Payment.js'
import HttpUtil from '../assets/util/HttpUtil.js'
import Dispatcher from '../assets/util/Dispatcher'
import {initChangeListeners} from '../assets/util/ChangeListeners'
import {initAdminChangeListeners} from './util/ChangeListeners'
import {parseNewValidationErrors} from '../assets/util/FieldValidator.js'
import {applicationObjectWithValidationErrors} from './util/ApplicationObjectValidator.js'
import {paymentWithValidationErrors} from './util/PaymentValidator.js'
import {hideBusy} from '../assets/util/HtmlUtils.js'

const dispatcher = new Dispatcher()
const events = {
    updatePaymentForm: 'updatePaymentForm',
    updateEducationForm: 'updateEducationForm',
    route: 'route',
    search: 'search',
    updateField: 'updateField',
    submitForm: 'submitForm',
    fieldValidation: 'fieldValidation',
    logOut: 'logOut'
}

export function changeListeners() {
    return {...initChangeListeners(dispatcher, events), ...initAdminChangeListeners(dispatcher, events)}
}

export function initAppState(props) {
    document.domain = location.hostname
    const {tarjontaUrl, propertiesUrl, usersUrl, userUpdateUrl, applicationObjectUpdateUrl, paymentUpdateUrl} = props
    const initialState = {
        ['userUpdateUrl']:userUpdateUrl,
        ['applicationObjectUpdateUrl']:applicationObjectUpdateUrl,
        ['paymentUpdateUrl']:paymentUpdateUrl
    }
    const propertiesS = Bacon.fromPromise(HttpUtil.get(propertiesUrl))
    const serverUpdatesBus = new Bacon.Bus()
    const searchS = Bacon.mergeAll(dispatcher.stream(events.search),Bacon.once("")).skipDuplicates(_.isEqual)
    const fetchUsersFromServerS =
      searchS.flatMapLatest(search => {
          return Bacon.fromPromise(HttpUtil.get(`/hakuperusteetadmin/api/v1/admin?search=${search}`))
      })

    const updateRouteS = Bacon.mergeAll(dispatcher.stream(events.route),Bacon.once(document.location.pathname))
        .map(personOidInUrl)
        .skipDuplicates(_.isEqual)
        .filter(uniquePersonOid => uniquePersonOid ? true : false)
        .flatMap(uniquePersonOid => Bacon.fromPromise(HttpUtil.get(`/hakuperusteetadmin/api/v1/admin/${uniquePersonOid}`)))
        .merge(serverUpdatesBus)
    const updateApplicationObjectS = updateRouteS.flatMap(userdata => Bacon.fromArray(userdata.applicationObject))
    const tarjontaS = updateApplicationObjectS.map(ao => ao.hakukohdeOid).flatMap(fetchFromTarjonta).toEventStream()
    const updateFieldS = dispatcher.stream(events.updateField).merge(serverUpdatesBus)
    const fieldValidationS = dispatcher.stream(events.fieldValidation)
    const updateEducationFormS = dispatcher.stream(events.updateEducationForm)
    const updatePaymentFormS = dispatcher.stream(events.updatePaymentForm)

    const stateP = Bacon.update(initialState,
        [propertiesS], onStateInit,
        [fetchUsersFromServerS], onSearchUpdate,
        [searchS], onSearch,
        [tarjontaS], onTarjontaValue,
        [updateRouteS],onUpdateUser,
        [updateEducationFormS], onUpdateEducationForm,
        [updatePaymentFormS], onUpdatePaymentForm,
        [updateFieldS], onUpdateField,
        [fieldValidationS], onFieldValidation)

    const formSubmittedS = stateP.sampledBy(dispatcher.stream(events.submitForm), (state, form) => ({state, form}))
    const userDataFormSubmitS = formSubmittedS.filter(({form}) => form === 'userDataForm').flatMapLatest(({state}) => submitUserDataToServer(state))
    serverUpdatesBus.plug(userDataFormSubmitS)
    userDataFormSubmitS.onValue((_) => hideBusy(document.getElementById('userDataForm')))

    const educationFormSubmitS = formSubmittedS.filter(({form}) => form.match(new RegExp("educationForm_(.*)"))).flatMapLatest(({state, form}) => {
      const hakukohdeOid = form.match(new RegExp("educationForm_(.*)"))[1]
      const applicationObject = _.find(state.applicationObjects, ao => ao.hakukohdeOid === hakukohdeOid)
      return submitEducationDataToServer(state, applicationObject, document.getElementById(form)).map(userdata => {
          return {['form']: form, ['userdata']: userdata}
      })
    });
    serverUpdatesBus.plug(educationFormSubmitS.map(({hakukohdeOid, userdata}) => userdata))
    educationFormSubmitS.onValue(({form}) => hideBusy(document.getElementById(form)))

    const paymentFormSubmitS = formSubmittedS.filter(({form}) => form.match(new RegExp("payment_(.*)"))).flatMapLatest(({state, form}) => {
        const paymentId = form.match(new RegExp("payment_(.*)"))[1]
        const payment = _.find(state.payments, payment => payment.id == paymentId)
        return submitPaymentDataToServer(state, payment, document.getElementById(form)).map(userdata => {
            return {['form']: form, ['userdata']: userdata}
        })
    });
    serverUpdatesBus.plug(paymentFormSubmitS.map(({form, userdata}) => userdata))
    paymentFormSubmitS.onValue(({form}) => hideBusy(document.getElementById(form)))

    function onSearch(state) {
        return {...state, ['isSearching']: true}
    }
    function onStateInit(state, properties) {
        return {...state, properties}
    }
    function onSearchUpdate(state, users) {
        return {...state, ['users']: users, ['isSearching']: false}
    }
    function onUpdatePaymentForm(state, payment) {
        function decorateWithErrors(pp) {
            const pFromServer = _.find(state.fromServer.payments, p => p.id == payment.id)
            return {...paymentWithValidationErrors(_.isMatch(pp, pFromServer) ? withNoChanges(pp) : withChanges(pp))}
        }
        var updatedPayments = _.map(state.payments, (oldP => oldP.id == payment.id ? decorateWithErrors(payment) : oldP))
        return {...state, ['payments']: updatedPayments}
    }
    function onUpdateEducationForm(state, newAo) {
        function decorateWithErrors(a) {
            const aoFromServer = _.find(state.fromServer.applicationObject, ao => ao.id == newAo.id)
            return {...applicationObjectWithValidationErrors(_.isMatch(a, aoFromServer) ? withNoChanges(a) : withChanges(a))}
        }
        var updatedAos = _.map(state.applicationObjects, (oldAo => oldAo.id == newAo.id ? decorateWithErrors(newAo) : oldAo))
        return {...state, ['applicationObjects']: updatedAos}
    }
    function onTarjontaValue(state, tarjonta) {
        const currentTarjonta = state.tarjonta || []
        const newTarjonta = {...currentTarjonta, [tarjonta.hakukohdeOid]: tarjonta}
        return {...state, ['tarjonta']: newTarjonta}
    }
    function onUpdateField(state, {field, value}) {
        return {...state, [field]: value}
    }
    function onUpdateUser(state, user) {
        const referenceUser = withPartialPersonId(decorateWithHasPersonId(user.user))
        const fromServer = {['fromServer']: {...user, ['user']: referenceUser}}
        const payments = {['payments']: _.map(user.payments, withNoChanges)}
        const applicationObjects = {['applicationObjects']: _.map(user.applicationObject, withNoChanges)}
        return {...state,...withNoChanges(referenceUser),...payments,...applicationObjects,...fromServer}
    }
    function onFieldValidation(state, {field, value}) {
        const newValidationErrors = parseNewValidationErrors(state, field, value)
        return {...state, ['validationErrors']: {...newValidationErrors, ...validateIfNoChanges(state, state.fromServer.user)}}
    }
    function fetchFromTarjonta(hakukohde) {
        return Bacon.fromPromise(HttpUtil.get(tarjontaUrl + "/" + hakukohde))
    }
    function personOidInUrl(url) {
        var match = url.match(new RegExp("oppija/(.*)"))
        return match ? match[1] : null
    }
    // Helper functions
    function withPartialPersonId(obj) {
        const HETU_LEN = 11
        return obj.personId.length == HETU_LEN ? {...obj, ['personId']: obj.personId.substring(HETU_LEN - 5, HETU_LEN)} : obj
    }
    function withChanges(obj) {
        const currentValidationErrors = obj.validationErrors || {}
        return {...obj, ['validationErrors']: {...currentValidationErrors, ['noChanges']: null}}
    }
    function withNoChanges(obj) {
        const currentValidationErrors = obj.validationErrors || {}
        return {...obj, ['validationErrors']: {...currentValidationErrors, ['noChanges']: "required"}}
    }
    function decorateWithHasPersonId(user) {
        return user.personId ? {...user, ['hasPersonId']: true} : {...user, ['personId']: "", ['hasPersonId']: false}
    }
    function validateIfNoChanges(user, referenceUser) {
        return {['noChanges']: _.isMatch(user, referenceUser) ? "required" : null}
    }
    return stateP
}