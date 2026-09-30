package com.github.xingjianluo.storagevolumeprovisionerjava.tests;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * REST 入口集成测试：覆盖供给、绑定、不可满足原因、回收流转、
 * 快照原子恢复与旧格式升级，确认 HTTP 层暴露的结果/代码与服务层语义一致。
 */
@SpringBootTest
@AutoConfigureMockMvc
class WebApiIntegrationTest {

    @Autowired
    private MockMvc mvc;
    private final ObjectMapper json = new ObjectMapper();

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
            httpPost(String url, Object body) throws Exception {
        String payload = body == null ? "{}" : json.writeValueAsString(body);
        return MockMvcRequestBuilders.post(url)
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload);
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
            httpPost(String url) throws Exception {
        return httpPost(url, null);
    }

    private JsonNode jsonOf(MvcResult r) throws Exception {
        return json.readTree(r.getResponse().getContentAsString());
    }

    private Map<String, Object> volumeBody(String name, long bytes, String policy) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("capacityBytes", bytes);
        m.put("accessModes", List.of("RWO"));
        m.put("storageClass", "default");
        m.put("reclaimPolicy", policy);
        return m;
    }

    private Map<String, Object> claimBody(String name, long bytes, String sc) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("capacityBytes", bytes);
        m.put("accessModes", List.of("RWO"));
        m.put("storageClass", sc);
        return m;
    }

    @Test
    void provision_bind_mismatch_reasons_areDistinguishable() throws Exception {
        // 两块卷：一块容量不足，一块存储类不同
        mvc.perform(httpPost("/api/volumes",
                volumeBody("small", 512L * 1024 * 1024, "Retain")));
        mvc.perform(httpPost("/api/volumes", volumeBody("ssd", 1024L * 1024 * 1024, "Retain")));

        // 重名注册 -> 400
        mvc.perform(httpPost("/api/volumes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(
                                volumeBody("ssd", 1024L * 1024 * 1024, "Retain"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        // 容量不足 -> PENDING/INSUFFICIENT_CAPACITY（同存储类下只有 small）
        Map<String, Object> big = claimBody("big", 1024L * 1024 * 1024 * 2L, "default");
        mvc.perform(httpPost("/api/claims")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(big)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.phase").value("PENDING"))
                .andExpect(jsonPath("$.reason").value("INSUFFICIENT_CAPACITY"));

        // 存储类不匹配
        Map<String, Object> hdd = claimBody("hdd", 1024L * 1024 * 1024, "hdd");
        mvc.perform(httpPost("/api/claims")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(hdd)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.phase").value("PENDING"))
                .andExpect(jsonPath("$.reason").value("STORAGE_CLASS_NOT_MATCHED"));

        // 显式绑定到容量不足的卷 -> 422 + 稳定代码
        mvc.perform(httpPost("/api/claims/big/bind/small"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CAPACITY_NOT_MATCHED"));
    }

    @Test
    void duplicateBind_conflict_isReturnedOverHttp() throws Exception {
        mvc.perform(httpPost("/api/volumes", volumeBody("solo", 1024L * 1024 * 1024, "Retain")));
        mvc.perform(httpPost("/api/claims")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(
                                claimBody("one", 1024L * 1024 * 1024, "default"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.phase").value("BOUND"));

        mvc.perform(httpPost("/api/claims", claimBody("two", 1024L * 1024 * 1024, "default")));
        mvc.perform(httpPost("/api/claims/two/bind/solo"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VOLUME_ALREADY_BOUND"));
    }

    @Test
    void recycleFlow_overHttp_returnsToAvailable() throws Exception {
        mvc.perform(httpPost("/api/volumes", volumeBody("cyc", 1024L * 1024 * 1024, "Recycle")));
        mvc.perform(httpPost("/api/claims")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(
                                claimBody("c1", 1024L * 1024 * 1024, "default"))))
                .andExpect(jsonPath("$.phase").value("BOUND"));
        mvc.perform(httpPost("/api/claims/c1/release"))
                .andExpect(jsonPath("$.phase").value("RELEASED"))
                .andExpect(jsonPath("$.volumeResult").value("AVAILABLE"));
        // 删除 BOUND 被拒绝；此时卷 AVAILABLE 可删
        mvc.perform(delete("/api/volumes/cyc")).andExpect(status().isNoContent());
    }

    @Test
    void deleteBoundVolume_overHttp_isConflict() throws Exception {
        mvc.perform(httpPost("/api/volumes", volumeBody("bd", 1024L * 1024 * 1024, "Retain")));
        mvc.perform(httpPost("/api/claims", claimBody("bc", 1024L * 1024 * 1024, "default")));
        mvc.perform(delete("/api/volumes/bd"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ILLEGAL_TRANSITION"));
    }

    @Test
    void snapshotCreateAndRestore_overHttp() throws Exception {
        mvc.perform(httpPost("/api/volumes", volumeBody("sv", 1024L * 1024 * 1024, "Retain")));
        mvc.perform(httpPost("/api/claims", claimBody("sc", 1024L * 1024 * 1024, "default")));
        mvc.perform(put("/api/volumes/sv/data")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("data", Map.of("k", "v1")))))
                .andExpect(status().isOk());
        mvc.perform(httpPost("/api/volumes/sv/snapshots")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("snapshotName", "snap1"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("snap1"));

        mvc.perform(put("/api/volumes/sv/data")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("data", Map.of("k", "v2")))))
                .andExpect(status().isOk());
        mvc.perform(httpPost("/api/volumes/sv/snapshots/snap1/restore"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.k").value("v1"));
    }

    @Test
    void legacyUpgrade_v1AndV3OverHttp() throws Exception {
        Map<String, Object> v1 = Map.of(
                "version", 1,
                "fields", Map.of("name", "ancient", "size", "2Gi", "mode", "RWM"));
        mvc.perform(httpPost("/api/legacy/upgrade")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(v1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.capacityBytes").value(2L * 1024 * 1024 * 1024))
                .andExpect(jsonPath("$.reclaimPolicy").value("RETAIN"))
                .andExpect(jsonPath("$.storageClass").value("default"));

        Map<String, Object> v3 = Map.of(
                "version", 3,
                "fields", Map.of("name", "future"));
        mvc.perform(httpPost("/api/legacy/upgrade")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(v3)))
                .andExpect(status().isUpgradeRequired())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_RECORD_VERSION"));

        Map<String, Object> broken = Map.of(
                "version", 1,
                "fields", Map.of("name", "x", "mode", "RW"));
        mvc.perform(httpPost("/api/legacy/upgrade")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(broken)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("LEGACY_MISSING_FIELD"));
    }
}
