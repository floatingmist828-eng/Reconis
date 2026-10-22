package cn.reconis.casework;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import cn.reconis.common.*;
import cn.reconis.identity.Access;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

class CaseControllerTest {
  private Db db;
  private Access access;
  private FileStore files;
  private CaseController controller;
  private Map<String, Object> row;
  private static final String VERSION = "0000000000000001";

  @BeforeEach
  void setUp() {
    db = mock(Db.class);
    access = mock(Access.class);
    files = mock(FileStore.class);
    controller = new CaseController(db, access, files, mock(FullTextReadiness.class));
    row =
        new HashMap<>(
            Map.of(
                "status",
                "PROCESSING",
                "assigneeId",
                7L,
                "analysisText",
                "核对完成",
                "resolutionText",
                "待复核",
                "version",
                new byte[8]));
    when(access.caseRow(1L)).thenReturn(row);
    when(access.actor()).thenReturn(new RequestContext.Actor(7L, "cashier", "处理人", "MEMBER"));
  }

  @Test
  void closedCaseCannotReplaceEvidence() {
    row.put("status", "CLOSED");
    var error =
        assertThrows(
            ResponseStatusException.class,
            () ->
                controller.evidence(
                    1,
                    new MockMultipartFile("file", "proof.txt", "text/plain", new byte[] {1}),
                    VERSION));
    assertEquals(409, error.getStatusCode().value());
    verifyNoInteractions(files, db);
  }

  @Test
  void submittedCaseCannotReplaceEvidence() {
    row.put("status", "PENDING_REVIEW");
    assertThrows(
        ResponseStatusException.class,
        () ->
            controller.evidence(
                1,
                new MockMultipartFile("file", "proof.txt", "text/plain", new byte[] {1}),
                VERSION));
    verifyNoInteractions(files, db);
  }

  @Test
  void staleEvidenceUploadRemovesOnlyNewFiles(@TempDir Path temp) throws Exception {
    Path data = Files.write(temp.resolve("proof.txt"), new byte[] {1});
    Path metadata = Files.writeString(temp.resolve("proof.txt.meta.json"), "{}");
    when(files.write(anyString(), anyString(), any())).thenReturn("evidence/proof.txt");
    when(files.resolve("evidence/proof.txt")).thenReturn(data);
    when(files.resolve("evidence/proof.txt.meta.json")).thenReturn(metadata);
    when(db.exec(anyString(), anyMap())).thenReturn(0);
    var error =
        assertThrows(
            ResponseStatusException.class,
            () ->
                controller.evidence(
                    1,
                    new MockMultipartFile("file", "proof.txt", "text/plain", new byte[] {1}),
                    VERSION));
    assertEquals(409, error.getStatusCode().value());
    assertFalse(Files.exists(data));
    assertFalse(Files.exists(metadata));
  }

  @Test
  void rejectedReviewCannotCarryAnotherResult() {
    var error =
        assertThrows(
            ResponseStatusException.class,
            () ->
                controller.review(
                    1,
                    Map.of(
                        "approve",
                        false,
                        "resolutionCode",
                        "RECONCILED",
                        "evidenceResultId",
                        "999",
                        "reason",
                        "退回",
                        "version",
                        VERSION)));
    assertEquals(422, error.getStatusCode().value());
    verifyNoInteractions(db);
  }

  @Test
  void missingDecisionCannotImplicitlyReject() {
    assertThrows(
        ResponseStatusException.class,
        () -> controller.review(1, Map.of("reason", "意见", "version", VERSION)));
    verifyNoInteractions(db);
  }

  @Test
  void staleSubmitIsAConflict() {
    when(db.exec(anyString(), anyMap())).thenReturn(0);
    var error =
        assertThrows(
            ResponseStatusException.class, () -> controller.submit(1, Map.of("version", VERSION)));
    assertEquals(409, error.getStatusCode().value());
  }

  @Test
  void reassignedHandlerCannotSubmitOrEdit() {
    row.put("assigneeId", 8L);
    var error =
        assertThrows(
            ResponseStatusException.class, () -> controller.submit(1, Map.of("version", VERSION)));
    assertEquals(403, error.getStatusCode().value());
    verifyNoInteractions(db);
  }

  @Test
  void invalidVersionCannotBeUsedToWrite() {
    assertThrows(
        ResponseStatusException.class,
        () -> controller.assign(1, Map.of("assigneeId", "8", "version", "bad")));
    verifyNoInteractions(db);
  }
}
