package cn.reconis.casework;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import cn.reconis.common.*;
import cn.reconis.common.FileStore;
import cn.reconis.identity.Access;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.server.ResponseStatusException;

class CaseEvidenceServiceTest {
  @TempDir Path temp;
  FileStore files;
  CaseEvidenceService service;

  @BeforeEach void setup() throws Exception {
    files = new FileStore(temp.resolve("demo").toString(), "DEMO", "jdbc:sqlserver:ReconisDemo");
    service = new CaseEvidenceService(mock(Db.class), mock(Access.class), files);
  }

  private CaseEvidenceService.FileReference ref(String uri, String sha) {
    return new CaseEvidenceService.FileReference(uri, sha, "ORIGINAL_BILL", "8", "", false, false);
  }

  @Test void archiveRetainsTamperedBytesButLabelsMismatchAndRecordsBothHashes() throws Exception {
    byte[] original = "原始账单".getBytes(StandardCharsets.UTF_8);
    String uri = files.write("imports", ".xml", original);
    byte[] altered = "篡改金额".getBytes(StandardCharsets.UTF_8);
    Files.write(files.resolve(uri), altered);
    var content = new LinkedHashMap<String, byte[]>();
    var rows = service.verifyFiles(1, List.of(ref(uri, FileStore.sha(original))), true, content);
    var row = rows.getFirst();
    assertEquals("HASH_MISMATCH", row.get("verification"));
    assertEquals(FileStore.sha(altered), row.get("sha256"));
    assertTrue(((Set<?>) row.get("expectedSha256")).contains(FileStore.sha(original)));
    assertTrue(row.get("archivePath").toString().contains("hash-mismatch/"));
    assertArrayEquals(altered, content.get(row.get("archivePath")));
  }

  @Test void missingFileNeverLooksVerifiedAndCannotBeIncluded() {
    var content = new LinkedHashMap<String, byte[]>();
    var row = service.verifyFiles(1, List.of(ref("imports/missing.xml", "a".repeat(64))), true, content).getFirst();
    assertEquals("MISSING", row.get("verification"));
    assertEquals(false, row.get("included"));
    assertTrue(content.isEmpty());
  }

  @Test void sharedBillIsNotReadOrPackagedForMerchant() throws Exception {
    String uri = files.write("imports", ".xml", "other merchant secret".getBytes(StandardCharsets.UTF_8));
    var content = new LinkedHashMap<String, byte[]>();
    var r = new CaseEvidenceService.FileReference(uri, "", "ORIGINAL_BILL", "3", "", true, false);
    var row = service.verifyFiles(1, List.of(r), true, content).getFirst();
    assertEquals("RESTRICTED", row.get("verification"));
    assertFalse(row.containsKey("sha256"));
    assertTrue(content.isEmpty());
  }

  @Test void attachmentOwnedByDifferentCaseIsRejectedEvenWhenUriIsReferenced() throws Exception {
    String uri = files.write("evidence", ".txt", "sensitive".getBytes(StandardCharsets.UTF_8));
    files.jsonWrite(uri + ".meta.json", Map.of("caseId", 2, "uri", uri, "name", "secret.txt"));
    var r = new CaseEvidenceService.FileReference(uri, "", "CASE_ATTACHMENT", "1", "", false, true);
    var content = new LinkedHashMap<String, byte[]>();
    var row = service.verifyFiles(1, List.of(r), true, content).getFirst();
    assertEquals("METADATA_MISMATCH", row.get("verification"));
    assertTrue(content.isEmpty());
  }

  @Test void legacyEvidenceGetsCurrentHashWithoutInventingHistoricalVerification() throws Exception {
    byte[] bytes = "older evidence".getBytes(StandardCharsets.UTF_8);
    String uri = files.write("evidence", ".txt", bytes);
    files.jsonWrite(uri + ".meta.json", Map.of("caseId", 1, "uri", uri, "name", "old.txt"));
    var r = new CaseEvidenceService.FileReference(uri, "", "HISTORICAL_ATTACHMENT", "1", "", false, true);
    var row = service.verifyFiles(1, List.of(r), false, new HashMap<>()).getFirst();
    assertEquals("NO_BASELINE_HASH", row.get("verification"));
    assertEquals(FileStore.sha(bytes), row.get("sha256"));
    assertEquals(Set.of(), row.get("expectedSha256"));
  }

  @Test void traversalCannotReadOutsideEvidenceFolders() {
    var row = service.verifyFiles(1, List.of(ref("../private/key.pem", "")), true, new HashMap<>()).getFirst();
    assertEquals("INVALID_PATH", row.get("verification"));
  }

  @Test void revokedCasePermissionStopsBeforeAnyDatabaseOrFileReads() {
    Db db = mock(Db.class);
    Access access = mock(Access.class);
    FileStore store = mock(FileStore.class);
    when(access.caseRow(5)).thenThrow(Api.fail(404, "记录不存在或不可访问"));
    var restricted = new CaseEvidenceService(db, access, store);
    assertThrows(ResponseStatusException.class, () -> restricted.inspect(5));
    verifyNoInteractions(db, store);
  }

  @Test void diagnosisKeepsHistoricalMismatchAfterMatchedClosureAndUsesExactIntegerDifference() {
    var c = Api.params("id", 1L, "classification", "AMOUNT_MISMATCH", "status", "CLOSED",
        "resolutionCode", "RECONCILED", "resolutionText", "独立复核通过");
    var result = Api.params("id", 2L, "batchNo", "OLD", "classification", "AMOUNT_MISMATCH",
        "localAmountCent", 9007199254740993L, "channelAmountCent", 9007199254740991L);
    var diagnosis = CaseEvidenceService.diagnosis(c, List.of(result));
    var facts = (List<?>) diagnosis.get("facts");
    assertEquals("2", ((Map<?, ?>) facts.get(1)).get("differenceCent"));
    assertTrue(((Map<?, ?>) facts.get(0)).get("text").toString().contains("金额不符"));
    assertEquals("REVIEWED", ((Map<?, ?>) diagnosis.get("conclusion")).get("status"));
    assertEquals("UNCONFIRMED_HYPOTHESIS", ((Map<?, ?>) ((List<?>) diagnosis.get("hypotheses")).getFirst()).get("kind"));
  }

  @Test void zipManifestUsesStringIdsAndItsChecksumMatchesActualBytes() throws Exception {
    byte[] bytes = "evidence".getBytes(StandardCharsets.UTF_8);
    var build = new CaseEvidenceService.Build(Map.of("caseId", 9007199254740993L, "files", List.of()),
        Map.of("files/evidence/proof.txt", bytes));
    byte[] archive = service.zip(build);
    var entries = new HashMap<String, byte[]>();
    try (var in = new ZipInputStream(new ByteArrayInputStream(archive))) {
      for (var entry = in.getNextEntry(); entry != null; entry = in.getNextEntry()) entries.put(entry.getName(), in.readAllBytes());
    }
    var manifest = new ObjectMapper().readTree(entries.get("manifest.json"));
    assertTrue(manifest.get("caseId").isTextual());
    assertEquals("9007199254740993", manifest.get("caseId").asText());
    assertEquals(FileStore.sha(entries.get("manifest.json")) + "  manifest.json\n",
        new String(entries.get("manifest.sha256"), StandardCharsets.UTF_8));
    assertArrayEquals(bytes, entries.get("files/evidence/proof.txt"));
  }
}
