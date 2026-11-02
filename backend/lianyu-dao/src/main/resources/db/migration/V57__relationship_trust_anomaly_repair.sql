-- Repair trust scores that ran away from intimacy.
--
-- Root cause (predates the 2026-11 relationship rework): REPAIR_ATTEMPT awarded
-- +4 trust with 0 intimacy, so repeatedly saying sorry farmed trust to the cap.
-- Observed rows: trust=100/intimacy=32, 100/35, 92/20 -- impossible under the
-- current scoring, where every intimacy-bearing event also moves trust.
--
-- The code path is now gated (repair only counts while an injury is open), so this
-- is a one-shot history correction. Predicate is environment-agnostic: staging and
-- production converge without hardcoding user ids.
--
-- Rows with trust 60-80 at intimacy 20 are left alone: those can legitimately come
-- from comforting replies (+2 trust, +0 intimacy), which is intended behaviour.

UPDATE relationship_state
SET trust_score = 50
WHERE trust_score >= 90
  AND intimacy_score <= 40;
