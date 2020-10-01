import { Component, createElement, h, render } from "Preact";

// check martin's comments and work on those

function fetchPreviouslyOpened() {
  // check if localstorage is present in the browser
  if (!window.localstorage) {
  }
}

class RecentVisited extends Component {
  constructor() {
    super();
  }

  render(props, state) {
    h(
      "p",
      {
        classname: "hl-copy"
      },
      "This is some text"
    );
  }
}

render(h(RecentVisited), document.querySelector("#recent-docs-visited"));
