import { describeToolAction, extractToolSkillName } from '../toolPresentation'

describe('toolPresentation', () => {
  it('infers skill traces from generic tool calls when a skill identifier is present', () => {
    const tool = {
      name: 'Tool',
      input: {
        skill: 'business-domain-assistant'
      },
      output: 'Launching skill: business-domain-assistant'
    }

    expect(extractToolSkillName(tool)).toBe('business-domain-assistant')
    expect(describeToolAction(tool).kind).toBe('skill')
    expect(describeToolAction(tool).detail).toBe('business-domain-assistant')
  })
})
