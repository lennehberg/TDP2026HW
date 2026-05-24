# AI Usage Log

Per requirements §4.5. Tools used: **Claude Opus 4.7** in Cowork mode as
planner/orchestrator(aka the overlord), and **Claude Code (CLI)** on the same model as the
implementer. All generated code was reviewed and tested locally before
commit.

## Important prompts

- *"Let's start from the beginning. Go over the assignment requirements. make sure you understand them, mark down key information, useful things to know, where to learn more about resources that are mentioned, and remember what the overall goal is and what is required to implement it."*
  Produced the initial `BUILD_PLAN.md` and the Java + Spring Boot choice
  over the NestJS skeleton.

- *"ok. we'll move into planning our approach to each step later. now i want some resources i can use to learn more about these subjects"*

- *"Lets start designing [feature_name], with the goal of writing a detailed checklist for claude code and i to implement"* 

- *"Implementation feedback for phase-N."* 

- *"Phase 13 review and summary."*

## Collaboration rule given to Claude Code

Claude Code was instructed to operate as a *skeleton generator* with an
*ask-before-interesting* rule:

- **Write freely:** class files, signatures, imports, annotations, DTO
  records, config plumbing, trivial bodies (mappers, one-line controller
  pass-throughs, straightforward repo calls).
- **Pause and ask first:** before any method body with genuinely
  interesting logic — algorithms, validation, business rules, security
  decisions. Propose the approach in 1–2 sentences and ask whether the
  author wants to write it or have Claude Code draft it.

Reason: the assignment is also a learning exercise; the author wanted
hands-on time on the interesting decisions.
