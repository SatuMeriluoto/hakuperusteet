import React from 'react'
import Bacon from 'baconjs'
import _ from 'lodash'

import Countries from './Countries.jsx'
import CountryPaymentInfo from './CountryPaymentInfo.jsx'

export default class BaseEducation extends React.Component {
  render() {
    const state = this.props.state
    const controller = this.props.controller
    const lang = "en"
    return <div>
      <p>Please select your base education country.</p>
      <Countries countries={state.countries} controller={controller} lang={lang} />
      <CountryPaymentInfo country={state.country} />
    </div>
  }
}
