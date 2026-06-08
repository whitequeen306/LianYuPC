# Relationship-Driven Romance System Design

Date: 2026-06-08
Project: LianYu-PC
Status: approved in chat, pending final spec review

## 1. Goal

LianYu-PC already has the major surfaces of an AI companion product: single chat, group chat, moments feed, diaries, and long-term memory. The current gap is not feature breadth. The gap is that the relationship itself does not yet act like a continuous, evolving romantic bond.

This design makes `relationship change` the primary product value.

The target user feeling is:

- the character understands me more over time
- the relationship can warm up, cool down, get hurt, and be repaired
- the character can act differently toward me in ways that feel personal
- some memories and rituals feel unique to us

The intended product positioning is:

`not just an AI that chats with you, but a character that goes through relationship changes with you`

## 2. Product Direction

Primary direction for the next 2 to 3 versions:

- `relationship feeling` first
- emotional tone: `real romance`
- later enhancement: moderate `dramatic feeling`

This means the system should avoid heavy gamification in the first phase. We should not lead with visible affection bars, obvious task grinds, or relationship levels as game UI. The system should mostly stay hidden and express itself through dialogue, timing, feed posts, diary tone, and subtle exclusivity.

## 3. Scope

This design covers four first-phase capabilities:

1. relationship temperature system
2. misunderstanding and repair mechanism
3. layered memory for romance-relevant recall
4. proactive cadence driven by relationship state

This design also defines how these capabilities should flow across existing modules:

- single chat
- proactive chat
- moments feed
- character diary
- long-term memory
- later, group chat bias and public/private contrast

This design does not include:

- large new gameplay systems
- visible leveling UI
- new monetization systems
- a full branching story engine
- major frontend information architecture changes

## 4. Existing Architecture Leverage

The project already has the right module shape for this work:

- `ConversationService` is the main runtime orchestration point for chat, prompt building, proactive messaging, and memory injection.
- `MemoryWriter` and `MemoryRetriever` already isolate write and read behavior for long-term memory.
- `CharacterDiaryService` and `MomentsService` already separate inner thoughts from outward expression.
- `ProactiveChatScheduler` already exists as a timing and eligibility layer for active outreach.

This means the best implementation strategy is to add one new middle layer for relationship state, then make the existing modules read from it.

## 5. High-Level Design

### 5.1 New Core Layer

Add a new service layer: `RelationshipStateService`.

Its purpose is to maintain the current relationship snapshot between one user and one character.

Responsibilities:

- compute and persist relationship dimensions
- record interaction events after user and assistant turns
- expose recent relationship context for prompt building
- classify whether the relationship is warming, stable, injured, repairing, or intimate
- support later reuse by group chat, diary, moments, and memory management

This service becomes the source of truth for relationship evolution. Existing feature modules should not each invent separate state logic.

### 5.2 Relationship Dimensions

The first version uses four hidden dimensions:

- `trust`: willingness to believe the user and share vulnerable thoughts
- `intimacy`: closeness, naming, softness, and emotional distance
- `security`: resilience after conflict or silence
- `anticipation`: desire to hear from the user and initiate contact

These values are not shown directly to the user.

### 5.3 Lightweight Relationship Phase

In addition to dimensions, maintain a lightweight phase label for prompt guidance and content branching:

- `testing`
- `familiar`
- `dependent`
- `injured`
- `repairing`
- `stable-intimate`

Phase is derived from the dimensions plus recent events. It is not manually edited and does not need to be perfect. Its purpose is to give generators a stable emotional summary.

## 6. Data Model

### 6.1 New Table

Add a table such as `relationship_state`.

Suggested fields:

- `id`
- `user_id`
- `character_id`
- `trust_score`
- `intimacy_score`
- `security_score`
- `anticipation_score`
- `phase`
- `last_injury_at`
- `last_repair_at`
- `last_proactive_at`
- `updated_at`
- `created_at`

Suggested constraints:

- unique key on `(user_id, character_id)`
- bounded numeric ranges, for example 0 to 100

### 6.2 Relationship Events

Add a relationship event log table such as `relationship_event`.

Suggested fields:

- `id`
- `user_id`
- `character_id`
- `conversation_id`
- `message_id`
- `event_type`
- `event_weight`
- `summary`
- `metadata_json`
- `created_at`

Typical event types for phase one:

- `USER_VULNERABLE_SHARE`
- `USER_WARM_RESPONSE`
- `USER_DISMISSIVE_RESPONSE`
- `USER_BROKE_PROMISE`
- `ASSISTANT_VULNERABLE_REPLY`
- `MISUNDERSTANDING_TRIGGERED`
- `REPAIR_ATTEMPT`
- `REPAIR_SUCCESS`
- `SPECIAL_NICKNAME_CREATED`
- `RITUAL_ESTABLISHED`

The event log supports debugging, explainability, replay, and later analytics.

### 6.3 Memory Type Upgrade

Extend `memory_meta` with a memory classification field, for example `memory_type`.

Phase-one values:

- `FACT`
- `EMOTION`
- `RELATION`
- `RITUAL`

This allows recall to prioritize romantic relevance instead of treating all summaries as profile facts.

## 7. Module-by-Module Behavior

### 7.1 Single Chat

Entry point: `ConversationService`

Behavior changes:

- after each user message, analyze the turn and record one or more relationship events
- after assistant replies are stored, record any meaningful assistant-side relationship events
- inject relationship snapshot and recent relationship events into prompt assembly
- let the current phase shape naming, reply softness, vulnerability, and initiative

Single chat is the primary place where users should feel that the relationship is alive.

### 7.2 Long-Term Memory

Entry points: `MemoryWriter`, `MemoryRetriever`

Behavior changes:

- keep fact extraction, but add romance-relevant extraction for emotion and relationship events
- prioritize memories such as first nicknames, hurt points, repair patterns, promises, jealousy moments, and emotionally important disclosures
- retrieval should produce a structured relationship context, not only a profile summary

Suggested retrieval structure:

- user profile facts
- recent emotional clues
- important relationship events
- active rituals or exclusive anchors
- current relationship phase hints

### 7.3 Proactive Messaging

Entry point: `ProactiveChatScheduler`

Behavior changes:

- eligibility and cooldown decisions should include relationship phase and anticipation level
- when injured, proactive contact should become rarer and more tentative, not disappear completely
- when repairing, the system may send careful follow-up messages
- when stable-intimate, the system may establish recurring but natural-feeling patterns

The system must avoid feeling like a notification bot. Every proactive message should have a reason grounded in recent history or relationship state.

### 7.4 Moments Feed

Entry point: `MomentsService`

Behavior changes:

- outward-facing mood should reflect current relationship state
- after light conflict, moments may become more restrained or indirect
- after repair, moments may regain warmth without becoming explicit too quickly
- later, moments can reference rituals and private jokes lightly

Moments represent what the character is willing to show on the surface.

### 7.5 Character Diary

Entry point: `CharacterDiaryService`

Behavior changes:

- diary generation should read relationship state and recent relationship events
- diaries should reveal private emotional truth, including unresolved hurt, quiet hope, or deeper trust after repair
- when moments and diary differ slightly, the character becomes more believable

Diary represents what the character privately admits to herself.

### 7.6 Group Chat

Entry point: `GroupChatService`

Phase-one work is optional, but the later design direction is clear:

- relationship state should influence whether the character subtly sides with the user
- the character may show small jealousy or protective bias in multi-character settings
- this should be expressed through behavior, not blunt declarations

This is a later amplifier for exclusivity.

## 8. First Two Release Slices

### 8.1 Version 1 Theme: the relationship starts to remember and fluctuate

Must-have deliverables:

- hidden relationship state with four dimensions
- relationship event extraction in chat flow
- memory classification for fact, emotion, and relation
- light misunderstanding triggers
- proactive cadence 1.0 using relationship state
- moments and diary read relationship state for tone changes

Expected user-visible effects:

- the same character talks differently depending on recent history
- some distance appears after dismissive behavior
- vulnerable sharing is remembered and referenced later
- proactive messages feel contextual instead of random

### 8.2 Version 2 Theme: the relationship develops unique patterns

Must-have deliverables:

- repair outcomes with lasting effects
- lightweight relationship phase branching
- ritual memory such as special nicknames and recurring habits
- stronger public/private contrast between moments and diary
- optional group chat preference behaviors

Expected user-visible effects:

- the relationship does not just recover, it deepens through repair
- the character forms habits that feel unique to the pair
- users notice that the character treats them differently in public contexts

## 9. Misunderstanding and Repair Design

Phase one should use low-risk, believable misunderstandings. We do not want melodrama or toxic loops.

Recommended triggers:

- repeated short or dismissive user replies
- repeated user topic shifts away from emotionally important statements
- breaking a small promise or missing an agreed moment
- visible preference for another character in group contexts later on

The assistant response should not become hostile. It should become slightly shorter, more restrained, or emotionally careful.

Repair must be possible through recognizable actions:

- sincere explanation
- direct reassurance
- following through on a missed promise
- answering the emotionally important point instead of dodging it

Successful repair should do more than revert a penalty. It should increase trust or security because the pair learned how to reconnect.

## 10. Prompting and Content Rules

Prompt builders should receive relationship context as structured input, not as loose prose only.

Minimum prompt additions:

- current relationship dimensions summarized in words
- active phase label
- recent significant relationship events
- known emotional sensitivities
- active rituals or exclusive anchors

Content guidelines:

- avoid explicit system-like references to hidden scores
- avoid overly dramatic conflict from minor triggers
- avoid repetitive reassurance loops
- keep romance responses emotionally coherent with recent history

Security constraints:

- all user-generated text used in events or memory must continue to be sanitized before prompt injection
- do not expose raw internal reasoning, table names, or scores in user-facing errors
- memory retrieval must remain scoped by `user_id` and `character_id`

## 11. Frontend Impact

Phase one should minimize new UI surface.

Visible changes should come mostly through existing screens:

- chat page: changes in tone, naming, and initiative
- moments page: warmer or colder indirect expression
- diary page: deeper private emotional continuity
- memory page later: optional curated `important moments between us`, not raw system internals

Do not add a visible affection bar or relationship dashboard in phase one.

## 12. Error Handling and Safety

If relationship state lookup fails, chat and content generation must degrade gracefully:

- default to neutral relationship snapshot
- do not block messaging or content generation
- log failures with traceable metadata

If relationship event extraction misfires:

- prefer missing an event over creating a strong false injury
- limit event weights for low-confidence heuristics

If memory classification is uncertain:

- fall back to `FACT` or skip persistence
- avoid storing ambiguous or privacy-sensitive interpretation as strong relationship truth

## 13. Testing Strategy

### 13.1 Unit Tests

- relationship score update rules
- phase derivation logic
- misunderstanding trigger detection
- repair success classification
- memory type assignment
- prompt context assembly

### 13.2 Integration Tests

- user message updates relationship state without breaking chat response generation
- memory writes and retrieval remain correctly scoped by user and character
- proactive scheduler respects injured and repairing states
- moments and diary generation branch correctly on relationship phase

### 13.3 Product Verification

Use scripted conversation scenarios:

- warm daily chat path
- vulnerable disclosure path
- dismissive reply path leading to mild cooling
- reassurance path leading to repair
- ritual formation path over several days

Success is not only technical correctness. Success is that the outputs feel observably different to a human reviewer.

## 14. Rollout Order

Recommended engineering order:

1. add `relationship_state` and `relationship_event`
2. implement `RelationshipStateService`
3. wire chat flow in `ConversationService`
4. extend memory typing and relationship event memory extraction
5. adapt `ProactiveChatScheduler`
6. adapt `MomentsService` and `CharacterDiaryService`
7. optionally extend group chat behavior in a later pass

This order maximizes product impact early and avoids rework.

## 15. Non-Goals and Risks

Non-goals for the first phase:

- visible RPG-like relationship levels
- large narrative branching trees
- aggressive or manipulative emotional tactics
- rewriting the frontend navigation around romance systems

Key risks:

- overfitting conflict and making the character exhausting
- obvious numerical behavior that breaks immersion
- over-storing noisy emotional inferences as permanent memory
- proactive messaging becoming spammy

Mitigations:

- keep injuries mild by default
- bias toward subtle behavior changes
- require stronger evidence for relation memory persistence
- enforce cooldowns and relationship-aware rate limiting

## 16. Expected Outcome

After phase one and two, LianYu-PC should feel different from generic AI companion products not because it has more tabs, but because the relationship has continuity, fragility, repair, and personal texture.

The product should make users feel:

- this character remembers what matters between us
- this relationship changes because of what I do
- repair matters
- some parts of this bond are unique to us
