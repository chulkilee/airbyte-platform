@file:Suppress("ktlint:standard:package-name")

package io.airbyte.connector_builder.services

import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.kohsuke.github.GHBranch
import org.kohsuke.github.GHCommit
import org.kohsuke.github.GHContentUpdateResponse
import org.kohsuke.github.GHFileNotFoundException
import org.kohsuke.github.GHPullRequest
import org.kohsuke.github.GHRef
import org.kohsuke.github.GHRepository
import org.kohsuke.github.GitHub
import org.kohsuke.github.PagedSearchIterable

class GithubContributionServiceTest {
  var testConnectorImageName = "source-test"
  var testUserName = "testusername"

  private val githubMock = mockk<GitHub>()
  private val repoMock = mockk<GHRepository>()
  private val refMock = mockk<GHRef>()
  private val prMock = mockk<GHPullRequest>()
  private val pagedSearchPRMock = mockk<PagedSearchIterable<GHPullRequest>>()
  private val branchMock = mockk<GHBranch>()
  private val commitMock = mockk<GHCommit>()
  private val contentUpdateResponseMock = mockk<GHContentUpdateResponse>()

  private lateinit var contributionService: GithubContributionService

  @BeforeEach
  fun setUp() {
    every { githubMock.myself } returns
      mockk {
        every { login } returns testUserName
      }

    every { githubMock.getRepository(any()) } returns repoMock
    justRun { refMock.delete() }
    justRun { refMock.updateTo(any()) }

    every { refMock.getObject() } returns
      mockk {
        every { sha } returns "dummySha"
      }

    every { branchMock.merge("dummySha", any()) } returns commitMock
    every { repoMock.createRef(any(), any()) } returns refMock
    every { repoMock.getRef(any()) } returns refMock
    every { repoMock.createPullRequest(any(), any(), any(), any()) } returns prMock
    every { repoMock.getPullRequests(any()) } returns listOf(prMock)
    every { repoMock.getBranch(any()) } returns branchMock
    every { repoMock.getFileContent(any(), any()) } returns
      mockk {
        every { sha } returns "dummySha"
      }
    every { repoMock.defaultBranch } returns "main"
    every { repoMock.fork() } returns repoMock
    every { repoMock.createContent() } returns
      mockk {
        every { content(String()) } returns this
        every { path(any()) } returns this
        every { sha(any()) } returns this
        every { branch(any()) } returns this
        every { message(any()) } returns this
        every { commit() } returns contentUpdateResponseMock
      }

    every { pagedSearchPRMock.toList() } returns emptyList()

    every { repoMock.searchPullRequests() } returns
      mockk {
        every { isOpen() } returns this
        every { head(any()) } returns this
        every { list() } returns pagedSearchPRMock
      }

    contributionService =
      GithubContributionService(testConnectorImageName, "testtoken").apply {
        githubService = githubMock
      }
  }

  @Test
  fun `connectorDirectoryPath returns correct directory path`() {
    // get the connector directory path
    val connectorDirectoryPath = contributionService.connectorDirectoryPath

    // assert that the connector directory path is correct
    assertEquals("airbyte-integrations/connectors/$testConnectorImageName", connectorDirectoryPath)
  }

  @Test
  fun `contributionBranchName returns correct branch name`() {
    // get the contribution branch name
    val contributionBranchName = contributionService.contributionBranchName

    // assert that the contribution branch name is correct
    assertEquals("$testUserName/builder-contribute/$testConnectorImageName", contributionBranchName)
  }

  @Test
  fun `constructConnectorFilePath returns correct path`() {
    val expectedPath = "airbyte-integrations/connectors/$testConnectorImageName/dummyFile.txt"
    val actualPath = contributionService.constructConnectorFilePath("dummyFile.txt")
    assertEquals(expectedPath, actualPath)
  }

  @Test
  fun `createBranch successfully creates a new branch`() {
    val branchRef = contributionService.createBranch("new-feature-branch", repoMock)
    assertNotNull(branchRef)
  }

  @Test
  fun `getBranchRef returns null when branch does not exist`() {
    every { repoMock.getRef(any()) } throws GHFileNotFoundException("Branch does not exist")
    assertNull(contributionService.getBranchRef("non-existing-branch", repoMock))
  }

  @Test
  fun `getExistingOpenPullRequest returns null when no open PR exists`() {
    every { repoMock.getBranch(any()) } returns null
    assertNull(contributionService.getExistingOpenPullRequest())
  }

  @Test
  fun `deleteBranch deletes the specified branch`() {
    every { refMock.delete() } returns Unit
    assertDoesNotThrow { contributionService.deleteBranch("feature-to-delete", repoMock) }
  }

  @Test
  fun `getExistingFileSha returns null when file does not exist`() {
    every { repoMock.getFileContent(any(), any()) } throws GHFileNotFoundException("File does not exist")
    assertNull(contributionService.getExistingFileSha("non-existing-file.txt"))
  }

  @Test
  fun `createPullRequest creates a new pull request successfully`() {
    assertNotNull(contributionService.createPullRequest())
  }

  @Test
  fun `prepareBranchForContribution with existing branch and no PR deletes branch`() {
    every { repoMock.getRef(any()) } returns refMock
    every { pagedSearchPRMock.toList() } returns emptyList()

    contributionService.prepareBranchForContribution()

    verify { refMock.delete() }
  }

  @Test
  fun `prepareBranchForContribution with existing branch and a PR updates branch`() {
    every { repoMock.getRef(any()) } returns refMock
    every { pagedSearchPRMock.toList() } returns listOf(prMock)

    contributionService.prepareBranchForContribution()

    verify(exactly = 0) { refMock.delete() }
    verify { refMock.updateTo(any()) }
    verify { branchMock.merge("dummySha", "Merge latest changes from main branch") }
  }

  @Test
  fun `prepareBranchForContribution with no existing branch creates new branch`() {
    every {
      repoMock.getRef("refs/heads/$testUserName/builder-contribute/$testConnectorImageName")
    } throws GHFileNotFoundException("Branch does not exist")
    every { repoMock.createRef(any(), any()) } returns refMock

    contributionService.prepareBranchForContribution()

    verify { repoMock.createRef(any(), any()) }
  }
}
