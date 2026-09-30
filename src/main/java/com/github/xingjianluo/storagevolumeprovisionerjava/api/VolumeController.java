package com.github.xingjianluo.storagevolumeprovisionerjava.api;

import com.github.xingjianluo.storagevolumeprovisionerjava.api.dto.DataRequest;
import com.github.xingjianluo.storagevolumeprovisionerjava.api.dto.OperationResponse;
import com.github.xingjianluo.storagevolumeprovisionerjava.api.dto.VolumeRequest;
import com.github.xingjianluo.storagevolumeprovisionerjava.api.dto.VolumeView;
import com.github.xingjianluo.storagevolumeprovisionerjava.service.VolumeService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** 卷供给与数据读写入口。 */
@RestController
@RequestMapping("/api/volumes")
public class VolumeController {

    private final VolumeService volumeService;

    public VolumeController(VolumeService volumeService) {
        this.volumeService = volumeService;
    }

    @PutMapping
    public VolumeView provision(@RequestBody VolumeRequest request) {
        return volumeService.provision(request);
    }

    @GetMapping("/{name}")
    public VolumeView get(@PathVariable String name) {
        return volumeService.get(name);
    }

    @GetMapping
    public List<VolumeView> list() {
        return volumeService.list();
    }

    @PostMapping("/{name}/data")
    public OperationResponse writeData(@PathVariable String name, @RequestBody DataRequest request) {
        volumeService.writeData(name, request);
        return OperationResponse.ok("DATA_WRITTEN", "数据已写入卷 " + name);
    }

    @GetMapping("/{name}/data")
    public OperationResponse readData(@PathVariable String name,
                                      @RequestParam long offset,
                                      @RequestParam int length) {
        return OperationResponse.ok("DATA_READ", "读取成功", volumeService.readData(name, offset, length));
    }
}
