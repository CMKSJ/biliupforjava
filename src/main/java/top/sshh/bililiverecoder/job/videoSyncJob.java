package top.sshh.bililiverecoder.job;

import com.alibaba.fastjson.JSON;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import top.sshh.bililiverecoder.entity.RecordHistory;
import top.sshh.bililiverecoder.entity.RecordHistoryPart;
import top.sshh.bililiverecoder.entity.RecordRoom;
import top.sshh.bililiverecoder.entity.data.BiliVideoInfoResponse;
import top.sshh.bililiverecoder.repo.RecordHistoryPartRepository;
import top.sshh.bililiverecoder.repo.RecordHistoryRepository;
import top.sshh.bililiverecoder.repo.RecordRoomRepository;
import top.sshh.bililiverecoder.service.impl.LiveMsgService;
import top.sshh.bililiverecoder.service.impl.RecordBiliPublishService;
import top.sshh.bililiverecoder.util.BiliApi;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.List;

@Slf4j
@Component
public class videoSyncJob {

    @Value("${record.work-path}")
    private String workPath;

    @Autowired
    RecordBiliPublishService publishService;

    @Autowired
    RecordRoomRepository roomRepository;

    @Autowired
    RecordHistoryRepository historyRepository;

    @Autowired
    RecordHistoryPartRepository partRepository;

    @Autowired
    private LiveMsgService liveMsgService;


    // 定时查询直播历史，每五分钟验证一下是否发布成功
    @Scheduled(fixedDelay = 300000, initialDelay = 5000)
    public void syncVideo() {
        //查询出所有需要同步的录播记录
        log.info("同步视频分p cid 开始");
        for (RecordHistory next : historyRepository.findByBvIdNotNullAndPublishIsTrueAndCodeLessThan(0)) {
            BiliVideoInfoResponse videoInfoResponse = BiliApi.getVideoInfo(next.getBvId());
            next.setCode(videoInfoResponse.getCode());
            if (videoInfoResponse.getData() != null) {
                BiliVideoInfoResponse.BiliVideoInfo data = videoInfoResponse.getData();
                next.setAvId(data.getAid());
                next.setBvId(data.getBvid());
                next.setCoverUrl(data.getPic());
            }
            next = historyRepository.save(next);
            if (videoInfoResponse.getCode() == 0) {
                RecordRoom recordRoom = roomRepository.findByRoomId(next.getRoomId());
                List<BiliVideoInfoResponse.BiliVideoInfoPart> pages = videoInfoResponse.getData().getPages();
                for (BiliVideoInfoResponse.BiliVideoInfoPart page : pages) {
                    RecordHistoryPart part = partRepository.findByHistoryIdAndTitle(next.getId(), page.getPart());
                    if (part != null) {
                        part.setCid(page.getCid());
                        part.setPage(page.getPage());
                        part.setDuration(page.getDuration());
                        part = partRepository.save(part);

                        //如果配置成发布完成后删除则删除文件
                        String filePath = part.getFilePath();
                        if(recordRoom != null && recordRoom.getDeleteType() == 2){
                            File file = new File(filePath);
                            boolean delete = file.delete();
                            if(delete){
                                log.error("{}=>文件删除成功！！！", filePath);
                            }else {
                                log.error("{}=>文件删除失败！！！", filePath);
                            }
                        }else if(recordRoom != null && StringUtils.isNotBlank(recordRoom.getMoveDir()) && recordRoom.getDeleteType() == 5){

                            String fileName = filePath.substring(filePath.lastIndexOf("/") + 1, filePath.lastIndexOf("."));
                            String startDirPath = filePath.substring(0,filePath.lastIndexOf('/')+1);
                            String toDirPath = recordRoom.getMoveDir() + filePath.substring(0,filePath.lastIndexOf('/')+1).replace(workPath, "");
                            File toDir = new File(toDirPath);
                            if(!toDir.exists()){
                                toDir.mkdirs();
                            }
                            File startDir = new File(startDirPath);
                            File[] files = startDir.listFiles((file, s) -> s.startsWith(fileName));
                            if(files != null && files.length >0){
                                for (File file : files) {
                                    if(! filePath.startsWith(workPath)){
                                        part.setFileDelete(true);
                                        part = partRepository.save(part);
                                        continue;
                                    }
                                    try {
                                        Files.move(Paths.get(file.getPath()), Paths.get(toDirPath + file.getName()),
                                                StandardCopyOption.REPLACE_EXISTING);
                                        part.setFilePath(toDirPath + file.getName());
                                        part.setFileDelete(true);
                                        part = partRepository.save(part);
                                        log.error("{}=>文件移动成功！！！", file.getName());
                                    }catch (Exception e){
                                        log.error("{}=>文件移动失败！！！", file.getName());
                                    }
                                }
                            }
                        }
                        //解析弹幕入库
                        liveMsgService.processing(part);
                        log.info("同步视频分p 成功==>{}", JSON.toJSONString(part));
                    }
                }

            }
        }

    }
}
