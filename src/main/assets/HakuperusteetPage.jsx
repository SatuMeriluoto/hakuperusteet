import React from 'react'
import _ from 'lodash'

import style from './css/hakuperusteet.less'

import {showUserDataForm, showVetumaStart, showHakuList} from './AppLogic.js'
import Header from './Header.jsx'
import Session from './session/Session.jsx'
import ProgramInfo from './ProgramInfo.jsx'
import Footer from './Footer.jsx'
import VetumaResultWrapper from './vetuma/VetumaResultWrapper.jsx'
import UserDataForm from './userdata/UserDataForm.jsx'
import VetumaStart from './vetuma/VetumaStart.jsx'
import HakuList from './HakuList.jsx'

export default class HakuperusteetPage extends React.Component {
  render() {
    const state = this.props.state
    const controller = this.props.controller
    return <div>
      <Header />
      <ProgramInfo state={state} />
      <Session state={state} />
      { showUserDataForm(state) ? <UserDataForm state={state} controller={controller}/> : null}
      { showVetumaStart(state) ? <VetumaStart state={state} /> : null}
      { showHakuList(state) ? <HakuList state={state} /> : null}
      <VetumaResultWrapper state={state}/>
      <Footer />
    </div>
  }
}
