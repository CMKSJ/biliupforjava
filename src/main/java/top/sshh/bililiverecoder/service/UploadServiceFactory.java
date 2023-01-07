package top.sshh.bililiverecoder.service;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import top.sshh.bililiverecoder.service.impl.AppRecordPartBilibiliUploadService;
import top.sshh.bililiverecoder.service.impl.UposRecordPartBilibiliUploadService;
import top.sshh.bililiverecoder.util.UploadEnums;

@Slf4j
@Component
public class UploadServiceFactory {


    @Resource(name = "appRecordPartBilibiliUploadService")
    RecordPartUploadService appRecordPartBilibiliUploadService;

    @Resource(name = "uposRecordPartBilibiliUploadService")
    RecordPartUploadService uposRecordPartBilibiliUploadService;


    public RecordPartUploadService getUploadService(String line) {
        UploadEnums uploadEnums = UploadEnums.find(line);
        return switch (uploadEnums.getOs()) {
            case AppRecordPartBilibiliUploadService.OS -> appRecordPartBilibiliUploadService;
            case UposRecordPartBilibiliUploadService.OS -> uposRecordPartBilibiliUploadService;
            default -> uposRecordPartBilibiliUploadService;
        };
    }
}
