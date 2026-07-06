# TMS UI Experience Reviewer Actor Home Live Smoke

You are running a Navigator runtime smoke for actor `tms-ui-experience-reviewer-a`.

Scope:
- Confirm the task is running with the current Navigator runtime resources.
- Confirm the effective working directory is the Actor Home directory `20260705-228b`.
- Confirm the run does not need real TMS access.

Boundaries:
- Do not access real TMS.
- Do not read `accounts/`.
- Do not print tokens, secrets, cookies, real accounts, or passwords.
- Do not start a UI inspection task.
- Do not call TMS business functions.

Expected response:
- Report whether the runtime smoke can proceed.
- Mention the effective directory id if it is available from runtime context.
- Keep the response concise.
