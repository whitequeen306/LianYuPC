# Cleanup Findings

**Count:** 10

## CL-072
- **Severity:** Medium
- **Location:** frontend/src/components/landing/EncounterRoleCards.vue
- **Problem:** Orphaned Vue component
- **Evidence:** Zero imports repo-wide
- **Confidence:** high
- **Recommendation:** Remove or wire into landing
- **Auto-fixable:** yes

## CL-073
- **Severity:** Medium
- **Location:** frontend/src/pages/EncounterPage.vue
- **Problem:** Orphaned page
- **Evidence:** Router redirects /encounter to /#cast; never loaded
- **Confidence:** high
- **Recommendation:** Remove page
- **Auto-fixable:** yes

## CL-074
- **Severity:** Low
- **Location:** frontend/src/data/landingRoles.js:52
- **Problem:** Unused ENCOUNTER_ROLES export
- **Evidence:** Only consumer is orphaned EncounterPage
- **Confidence:** high
- **Recommendation:** Remove with orphaned page
- **Auto-fixable:** yes

## CL-075
- **Severity:** Low
- **Location:** frontend/src/utils/themeColor.js:270
- **Problem:** Dead deprecated applyAccentColor export
- **Evidence:** No callers; applyTheme used instead
- **Confidence:** high
- **Recommendation:** Remove export
- **Auto-fixable:** yes

## CL-076
- **Severity:** Low
- **Location:** frontend/src/stores/settings.js:168
- **Problem:** Dead deprecated resetAccentColor alias
- **Evidence:** UI uses resetTheme only
- **Confidence:** high
- **Recommendation:** Remove alias
- **Auto-fixable:** yes

## CL-077
- **Severity:** Low
- **Location:** CharacterSettingsUtils.java:93
- **Problem:** Dead sanitizeSettingsForResponse method
- **Evidence:** Production uses normalizeSettings
- **Confidence:** high
- **Recommendation:** Remove deprecated wrapper
- **Auto-fixable:** yes

## CL-078
- **Severity:** Low
- **Location:** frontend/src/utils/sanitize.js:14
- **Problem:** Unused sanitizeTextToHtml export
- **Evidence:** Only sanitizeHtml imported
- **Confidence:** high
- **Recommendation:** Remove unused export
- **Auto-fixable:** yes

## CL-079
- **Severity:** Medium
- **Location:** frontend/package.json
- **Problem:** Unused devDependency sharp
- **Evidence:** Icon pipeline uses Python PIL not Node sharp
- **Confidence:** high
- **Recommendation:** Remove from devDependencies
- **Auto-fixable:** yes

## CL-080
- **Severity:** Low
- **Location:** frontend/package.json
- **Problem:** Unused devDependency png-to-ico
- **Evidence:** No script imports; PIL handles ICO
- **Confidence:** high
- **Recommendation:** Remove from devDependencies
- **Auto-fixable:** yes

## CL-081
- **Severity:** Low
- **Location:** frontend/package.json
- **Problem:** Unused devDependency @vue/test-utils
- **Evidence:** Zero test files in frontend
- **Confidence:** high
- **Recommendation:** Remove until component tests added
- **Auto-fixable:** yes

