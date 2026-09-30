package com.github.xingjianluo.storagevolumeprovisionerjava.api;

import com.github.xingjianluo.storagevolumeprovisionerjava.api.dto.OperationResponse;
import com.github.xingjianluo.storagevolumeprovisionerjava.api.dto.RestoreRequest;
import com.github.xingjianluo.storagevolumeprovisionerjava.api.dto.SnapshotRequest;
import com.github.xingjianluo.storagevolumeprovisionerjava.api.dto.SnapshotView;
import com.github.xingjianluo.storagevolumeprovisionerjava.service.SnapshotService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** 快照创建与恢复入口。 */
@RestController
@RequestMapping("/api/snapshots")
public class SnapshotController {

    private final SnapshotService snapshotService;

    public SnapshotController(SnapshotService snapshotService) {
        this.snapshotService = snapshotService;
    }

    @PutMapping("/volumes/{volumeName}")
    public SnapshotView create(@PathVariable String volumeName, @RequestBody SnapshotRequest request) {
        return snapshotService.createSnapshot(volumeName, request.name());
    }

    @PostMapping("/{name}/restore")
    public OperationResponse restore(@PathVariable String name, @RequestBody RestoreRequest request) {
        return snapshotService.restoreSnapshot(name, request);
    }

    @GetMapping
    public List<SnapshotView> list() {
        return snapshotService.listSnapshots();
    }
}
