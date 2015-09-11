import React from 'react'

import {emptySelectValue, createSelectOptions} from '../util/HtmlUtils.js'

export default class Nationality extends React.Component {
  constructor(props) {
    super()
    this.id = "nationality"
  }

  componentDidMount() {
    this.props.controller.valueChanges({ target: { id: this.id, value: emptySelectValue() }})
  }

  render() {
    const controller = this.props.controller
    const result = createSelectOptions(this.props.countries)

    return <div className="userDataFormRow">
      <label htmlFor={this.id}>Nationality</label>
      <select id={this.id} onChange={controller.valueChanges}>
        {result}
      </select>
    </div>
  }
}