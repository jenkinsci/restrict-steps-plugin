# Restrict Steps Plugin

## Introduction

This plugin allows you to block pipeline steps from being used in any context.
This is currently in early-phase development.

Useful for disabling steps available to users.

## Known gaps

The following gaps are desirable to fill.

- It will block steps called from global shared libraries and user pipelines
  alike.  Global pipeline libraries should be excluded.
- User vars in shared pipeline libraries are not covered.  I would like to have
  admin-only vars that can be called from global library but users not able to
  call them directly except through intended pipeline steps.
- Declarative pipeline is not currently covered.

## Getting started

Install the plugin and add a config file to the global managed files of the
[config file provider][1] plugin.

## Config as code

```yaml
unclassified:
  globalConfigFiles:
    configs:
      - custom:
          comment: Prevents users from calling these steps
          content: |-
            steps:
              - sh
          id: restricted-steps
          name: Restricted Pipeline Steps
          providerId: org.jenkinsci.plugins.configfiles.custom.CustomConfig
```

## LICENSE

Licensed under MIT, see [LICENSE](LICENSE.md)

[1]: https://plugins.jenkins.io/config-file-provider/
