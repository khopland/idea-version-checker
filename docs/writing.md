# Writing rules

Use [ASD-STE100, Issue 9](https://www.asd-ste100.org/assets/files/ASD-STE100_ISSUE9.pdf) for new and changed technical text.
Apply these rules to documentation, comments, and user messages.
The rules below are a project guide. They do not replace the standard or its dictionary.

- Use short sentences. Give one instruction per sentence.
- Use the imperative for instructions: “Select Check for Updates.”
- Use active voice. Identify the component that does the action.
- Use the same term for the same item.
- Use approved words with their approved meanings and parts of speech.
- Use established technical nouns and technical verbs when necessary.
- Use American English spelling.
- Keep instructions to 20 words per sentence or fewer.
- Keep descriptive sentences to 25 words or fewer.
- Put conditions before the actions that depend on them.
- Explain the next action after an error.

Use exact names for UI controls, API symbols, commands, files, and build systems.
Do not rename an API symbol only to change its English wording.
Do not change quoted diagnostics, acceptance evidence, or historical release notes to match this guide.

## Project terms

| Term | Meaning |
| --- | --- |
| Declaration | A dependency or plugin entry in a build file. |
| Selector | The version text declared in a build file, including a supported operator. |
| Build inputs | Files and settings that can affect a native check or an edit. |
| Snapshot | Captured build inputs and declarations for one check context. |
| Candidate | A published version that a check selected as a possible update. |
| Observation | A cached check result and its context, mode, and timing. |
| Coverage | The reported check outcomes for recognized declarations. |
| Update preview | A review of proposed declaration changes before application. |
| Result lease | A check that a cached result still authorizes an edit. |

Use “check” for version queries.
Use “reload” for a native build import request.
A successful reload request does not confirm that the import succeeded.
