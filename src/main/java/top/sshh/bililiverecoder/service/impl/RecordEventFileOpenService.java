package top.sshh.bililiverecoder.service.impl;

import com.alibaba.fastjson.JSON;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import top.sshh.bililiverecoder.entity.*;
import top.sshh.bililiverecoder.repo.*;
import top.sshh.bililiverecoder.service.RecordEventService;

import java.io.File;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

@Slf4j
@Component
public class RecordEventFileOpenService implements RecordEventService {

    @Value("${record.work-path}")
    private String workPath;

    @Autowired
    private BiliUserRepository biliUserRepository;

    @Autowired
    private RecordRoomRepository roomRepository;

    @Autowired
    private RecordHistoryRepository historyRepository;

    @Autowired
    private RecordHistoryPartRepository historyPartRepository;

    @Autowired
    private LiveMsgRepository liveMsgRepository;


    @Override
    public void processing(RecordEventDTO event) {
        RecordEventData eventData = event.getEventData();
        log.info("分p开始录制事件==>{}", eventData.getRelativePath());
        String sessionId = eventData.getSessionId();
        try {
            Thread.sleep(1000L);
        } catch (Exception e) {
            e.printStackTrace();
        }
        RecordRoom room = roomRepository.findByRoomId(eventData.getRoomId());
        if (room == null) {
            log.error("录制异常，录制历史没有创建，录制房间也没有创建！！！可能webhook请求顺序错误");
            room = new RecordRoom();
            room.setRoomId(eventData.getRoomId());
            room.setCreateTime(LocalDateTime.now());
            room.setUname(eventData.getName());
            room.setTitle(eventData.getTitle());
            room = roomRepository.save(room);
        } else {
            room.setUname(eventData.getName());
            room.setTitle(eventData.getTitle());
            room.setSessionId(eventData.getSessionId());
            room.setRecording(eventData.isRecording());
            room.setStreaming(eventData.isStreaming());
            room = roomRepository.save(room);
        }
        Optional<RecordHistory> historyOptional = historyRepository.findById(room.getHistoryId());
        RecordHistory history;
        //异常情况判断
        if (!historyOptional.isPresent()) {
            log.error("录制异常，录制历史没有创建，可能webhook请求顺序错误");

            history = new RecordHistory();
            history.setEventId(event.getEventId());
            history.setRoomId(room.getRoomId());
            history.setStartTime(LocalDateTime.now());
            history.setEndTime(LocalDateTime.now());
            history.setTitle(eventData.getTitle());
            history.setSessionId(eventData.getSessionId());
            history.setRecording(eventData.isRecording());
            history.setStreaming(eventData.isStreaming());
            history = historyRepository.save(history);
        } else {
            history = historyOptional.get();
        }
        String filePath = workPath + File.separator + eventData.getRelativePath();
        // 正常逻辑
        boolean existsPart = historyPartRepository.existsByFilePath(filePath);
        if(existsPart){
            log.error("eventId 查询分p已存在，filePath==>{}", filePath);
            return;
        }
        RecordHistoryPart part = new RecordHistoryPart();
        part.setEventId(event.getEventId());
        part.setTitle(LocalDateTime.now().format(DateTimeFormatter.ofPattern("MM月dd日HH点mm分ss秒")));
        part.setAreaName(eventData.getAreaNameChild());
        part.setRoomId(history.getRoomId());
        part.setHistoryId(history.getId());
        part.setFilePath(filePath);
        part.setFileSize(0L);
        part.setSessionId(eventData.getSessionId());
        part.setRecording(eventData.isRecording());
        part.setStartTime(LocalDateTime.now());
        part.setEndTime(LocalDateTime.now());
        part = historyPartRepository.save(part);
        log.info("分p开始录制事件,成功保存数据库==>{}", JSON.toJSONString(part));
        String relativePath = eventData.getRelativePath();
        history.setTitle(eventData.getTitle());
        history.setSessionId(eventData.getSessionId());
        history.setRecording(eventData.isRecording());
        history.setStreaming(eventData.isStreaming());
        history.setFilePath(workPath + File.separator + relativePath.substring(0, relativePath.lastIndexOf('/')));
        history.setEndTime(LocalDateTime.now());
        historyRepository.save(history);

    }
}
