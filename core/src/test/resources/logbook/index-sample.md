# Scratchpad

## Folders

| Folder | Holds |
|---|---|
| `alpha/` | the first synthetic topic |

## Waiting on the user

#### 90. A heading outside Open is no task

- **State:** open

## Open

### `alpha/`: the first synthetic topic

Prose between the topic heading and its first task is not part of any task.

#### 1. First synthetic task (Ticket-1)

- **Ticket:** TICKET-1
- **State:** open, two writers running
- **Next:** write the report, then
  run the gates on the merged tree
- **Documents:** `alpha/01-first.md`, `alpha/work/first-spec.md` (and `alpha/work/{a,b}-report.md`)
- **Hold:** an unknown key is ignored

#### 2. Second synthetic task

**State:** waiting on the user: pick a flavour
**Ask:** which flavour of synthetic task to build
and why it matters
**Next:** build it

Prose after a blank line is not part of any value.

**Documents:**
`alpha/02-second.md`
`alpha/03-third.md`

#### Heading without a number

- **State:** this task is skipped with its lines

#### 3. Third synthetic task, with decisions

- **State:** in progress
- **Needs you:**
  - choose the flavour (A or B)
  - approve the plan, which is long
    and wraps onto a second line
- **Next:** continue once decided

```
#### 4. A heading inside a code fence is no task
```

### `beta/`: the second synthetic topic

#### 5. Fourth synthetic task, one decision inline

- **State:** open
- **Needs you:** approve the budget
- **Documents:** none

#### 6. Fifth synthetic task, nothing held

- **State (2026-11-02):** qualified state
- **State:** plain state wins over the qualified one above
- **Needs you:** none
- **Next (later):** the qualified next
- **Next:** the plain next

#### 7. Sixth synthetic task, a bare section

Only a heading and prose.

## Scheduled reports

### 8. A scheduled report (scheduled)

- **State:** not an open task

#### 9. Nor is this

- **State:** open
