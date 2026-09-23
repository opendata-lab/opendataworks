// Moved into the SDK, where the composer that uses it now lives. The command
// list, the substring filter and the keyboard behaviour are the same code the
// portal and the widget have always run, so migrating a shell onto the element
// does not change how slash commands behave.
export * from '../../../packages/agent-conversation/src/core/slashCommands.js'
