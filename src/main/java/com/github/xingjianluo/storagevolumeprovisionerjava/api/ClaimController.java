package com.github.xingjianluo.storagevolumeprovisionerjava.api;

import com.github.xingjianluo.storagevolumeprovisionerjava.api.dto.ClaimRequest;
import com.github.xingjianluo.storagevolumeprovisionerjava.api.dto.ClaimView;
import com.github.xingjianluo.storagevolumeprovisionerjava.api.dto.OperationResponse;
import com.github.xingjianluo.storagevolumeprovisionerjava.service.BindingService;
import com.github.xingjianluo.storagevolumeprovisionerjava.service.ReclaimService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** 卷请求的创建、绑定、回收入口。 */
@RestController
@RequestMapping("/api/claims")
public class ClaimController {

    private final BindingService bindingService;
    private final ReclaimService reclaimService;

    public ClaimController(BindingService bindingService, ReclaimService reclaimService) {
        this.bindingService = bindingService;
        this.reclaimService = reclaimService;
    }

    @PutMapping
    public ClaimView create(@RequestBody ClaimRequest request) {
        return bindingService.createClaim(request);
    }

    @PostMapping("/{name}/bind")
    public OperationResponse bind(@PathVariable String name) {
        return bindingService.bind(name);
    }

    @PostMapping("/reconcile")
    public List<OperationResponse> reconcile() {
        return bindingService.reconcileAll();
    }

    @GetMapping("/{name}")
    public ClaimView get(@PathVariable String name) {
        return bindingService.getClaim(name);
    }

    @GetMapping
    public List<ClaimView> list() {
        return bindingService.listClaims();
    }

    @DeleteMapping("/{name}")
    public OperationResponse delete(@PathVariable String name) {
        return reclaimService.deleteClaim(name);
    }
}
