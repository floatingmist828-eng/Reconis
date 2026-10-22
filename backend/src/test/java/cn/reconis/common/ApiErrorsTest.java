package cn.reconis.common;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;

import org.junit.jupiter.api.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;

class ApiErrorsTest {
  private MockMvc mvc;

  @BeforeEach void setup() {
    RequestContext.set(null, "error-trace-test");
    mvc = MockMvcBuilders.standaloneSetup(new FailureController())
        .setControllerAdvice(new Api.Errors()).build();
  }

  @AfterEach void clear() { RequestContext.clear(); }

  @Test void unexpectedFailureReturnsTraceWithoutInternalDetails() throws Exception {
    mvc.perform(get("/failure"))
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.requestId").value("error-trace-test"))
        .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
        .andExpect(content().string(not(containsString("implementation-detail"))));
  }

  @Test void frameworkInputErrorsKeepTheirClientStatus() throws Exception {
    mvc.perform(get("/required"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.requestId").value("error-trace-test"));
  }

  @RestController static class FailureController {
    @GetMapping("/failure") String fail() { throw new IllegalStateException("implementation-detail"); }
    @GetMapping("/required") String required(@RequestParam String value) { return value; }
  }
}
