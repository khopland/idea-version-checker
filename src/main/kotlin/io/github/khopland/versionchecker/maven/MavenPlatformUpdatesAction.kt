package io.github.khopland.versionchecker.maven

import io.github.khopland.versionchecker.BulkUpdateAction
import io.github.khopland.versionchecker.UpdateMode
import io.github.khopland.versionchecker.core.UpdateScope

class MavenPlatformPatchUpdateAction : BulkUpdateAction(UpdateMode.PATCH, UpdateScope.MAVEN_PLATFORM)
class MavenPlatformMinorUpdateAction : BulkUpdateAction(UpdateMode.MINOR, UpdateScope.MAVEN_PLATFORM)
class MavenPlatformMajorUpdateAction : BulkUpdateAction(UpdateMode.MAJOR, UpdateScope.MAVEN_PLATFORM)
