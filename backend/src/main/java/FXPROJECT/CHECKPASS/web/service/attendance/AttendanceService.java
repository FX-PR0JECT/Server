package FXPROJECT.CHECKPASS.web.service.attendance;

import FXPROJECT.CHECKPASS.domain.common.exception.AttendanceAlreadyProcessed;
import FXPROJECT.CHECKPASS.domain.common.exception.AttendanceCodeMismatch;
import FXPROJECT.CHECKPASS.domain.common.exception.NotAttendanceCheckTime;
import FXPROJECT.CHECKPASS.domain.dto.LectureTimeCode;
import FXPROJECT.CHECKPASS.domain.entity.attendance.Attendance;
import FXPROJECT.CHECKPASS.domain.entity.attendance.AttendanceTokens;
import FXPROJECT.CHECKPASS.domain.entity.lectures.Lecture;
import FXPROJECT.CHECKPASS.domain.entity.users.Students;
import FXPROJECT.CHECKPASS.domain.entity.users.Users;
import FXPROJECT.CHECKPASS.domain.repository.QueryRepository;
import FXPROJECT.CHECKPASS.domain.repository.attendance.JpaAttendanceRepository;
import FXPROJECT.CHECKPASS.domain.repository.attendance.JpaAttendanceTokenRepository;
import FXPROJECT.CHECKPASS.web.common.utils.LectureWeekUtils;
import FXPROJECT.CHECKPASS.web.common.utils.RandomNumberUtils;
import FXPROJECT.CHECKPASS.web.common.utils.ResultFormUtils;
import FXPROJECT.CHECKPASS.web.form.requestForm.attendance.AttendanceInputForm;
import FXPROJECT.CHECKPASS.web.form.responseForm.resultForm.AttendanceTokenInformation;
import FXPROJECT.CHECKPASS.web.form.responseForm.resultForm.ResultForm;
import FXPROJECT.CHECKPASS.web.service.lectures.LectureService;
import FXPROJECT.CHECKPASS.web.service.users.UserService;
import com.querydsl.core.Tuple;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.convert.ConversionService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.*;

import static FXPROJECT.CHECKPASS.domain.common.constant.CommonMessage.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class AttendanceService {

    private final UserService userService;
    private final LectureService lectureService;
    private final LectureWeekUtils lectureWeekUtils;
    private final JpaAttendanceTokenRepository jpaAttendanceTokenRepository;
    private final JpaAttendanceRepository jpaAttendanceRepository;
    private final QueryRepository queryRepository;
    private final RandomNumberUtils randomNumberUtils;
    private final ConversionService conversionService;

    /**
     * 출석체크
     * @param loggedInUser 로그인된 유저
     * @param lectureCode 강의코드
     * @return 성공 : (출석 : 출석체크가 완료되었습니다. 지각 : 지각 처리되었습니다.), 실패 : 출석체크 시간이 아닙니다.
     */
    @Transactional
    public ResultForm attend(Students loggedInUser, Long lectureCode) {
        LocalDateTime now = LocalDateTime.now();
        LocalTime currentTime = now.toLocalTime().truncatedTo(ChronoUnit.MINUTES);

        Lecture lecture = lectureService.getLecture(lectureCode);
        List<LectureTimeCode> lectureTimeCodeList = lecture.getLectureTimeCode();

        String attendanceId = generateAttendanceId(loggedInUser, lectureCode);

        if (isAttendanceChecked(attendanceId)) {
            throw new AttendanceAlreadyProcessed();
        }

        for (LectureTimeCode lectureTimeCode : lectureTimeCodeList) {
            if (isCurrentLectureDay(lectureTimeCode)) {
                return checkAndSaveAttendance(attendanceId, currentTime, lectureTimeCode);
            }
        }
        throw new NotAttendanceCheckTime();
    }

    /**
     * 전자출석
     * @param loggedInUser 로그인된 유저
     * @param attendanceCode 출석코드
     * @return 성공 : 출석체크가 완료되었습니다  실패 : 출석체크 시간이 아닙니다. 또는 출석코드가 일치하지 않습니다.
     */
    @Transactional
    public ResultForm attend(Students loggedInUser, int attendanceCode) {
        AttendanceTokens attendanceToken = getAttendanceToken(attendanceCode);
        LocalDateTime expirationDate = attendanceToken.getExpirationDate();

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime currentDate = now.truncatedTo(ChronoUnit.MINUTES);

        if (currentDate.isAfter(expirationDate)) {
            throw new NotAttendanceCheckTime();
        }

        Lecture lecture = attendanceToken.getLecture();
        Long lectureCode = lecture.getLectureCode();

        String attendanceId = generateAttendanceId(loggedInUser, lectureCode);

        if (isAttendanceChecked(attendanceId)) {
            throw new AttendanceAlreadyProcessed();
        }

        saveAttendance(attendanceId, 1); // 출석

        return ResultFormUtils.getSuccessResultForm(COMPLETE_ATTENDANCE.getDescription());
    }

    /**
     * 모든 강의 출석현황 통계 구하기
     * @param loggedInUser 로그인된 유저
     * @return 사용자의 수강하고 있는 강의 출석현황 통계 Map
     */
    public Map<String, Map<Integer, Long>> getAllLectureAttendanceCounts(Students loggedInUser) {
        Map<String, Map<Integer, Long>> lectureAttendanceCounts = new TreeMap<>();

        List<Lecture> enrollmentList = queryRepository.getEnrollmentList(loggedInUser);
        for (Lecture lecture : enrollmentList) {
            String lectureName = lecture.getLectureName();
            Long lectureCode = lecture.getLectureCode();

            String attendanceId = generateMatchingAttendanceId(loggedInUser, lectureCode);

            List<Tuple> attendanceCountList = queryRepository.getAttendanceCountList(attendanceId);
            Map<Integer, Long> attendanceCounts = aggregateAndSortAttendanceCounts(attendanceCountList);
            lectureAttendanceCounts.put(lectureName, attendanceCounts);
        }
        return lectureAttendanceCounts;
    }

    /**
     * 특정 강의의 출석현황 구하기
     * @param loggedInUser 로그인된 유저
     * @param lectureCode 강의코드
     * @return 각 주차마다 출석현황이 담겨져 있는 Map
     */
    public Map<Integer, String> getLectureAttendanceCounts(Students loggedInUser, Long lectureCode) {
        Map<Integer, String> lectureAttendanceCounts = new TreeMap<>();

        String attendanceId = generateMatchingAttendanceId(loggedInUser, lectureCode);
        List<Attendance> attendanceList = queryRepository.getAttendanceList(attendanceId);

        for (Attendance attendance : attendanceList) {
            int attendanceWeek = Integer.parseInt(attendance.getAttendanceId().substring(20));
            String status = String.valueOf(attendance.getAttendanceStatus());

            if (lectureAttendanceCounts.containsKey(attendanceWeek)) {
                String existingStatus = lectureAttendanceCounts.get(attendanceWeek);
                String saveStatus = existingStatus + status;
                lectureAttendanceCounts.put(attendanceWeek, saveStatus);
            }
            lectureAttendanceCounts.put(attendanceWeek, status);
        }

        return lectureAttendanceCounts;
    }

    /**
     * 현재 출석인원 목록 구하기
     * @param lectureCode 강의코드
     * @return 현재 출석한 인원 목록 Map
     */
    public Map<Long, String> getPresentAttendanceUsers(Long lectureCode) {
        Map<Long, String> presentAttendanceUsers = new TreeMap<>();

        String week = String.valueOf(lectureWeekUtils.getWeek()); // 현재 주차
        String day = String.valueOf(LocalDateTime.now().getDayOfWeek().getValue() - 1); // 월(0) ~ 금(5)

        List<Attendance> attendanceList = queryRepository.getPresentAttendanceList(lectureCode.toString(), day, week);

        for(Attendance attendance : attendanceList) {
            String attendanceId = attendance.getAttendanceId();
            Long userId = Long.valueOf(attendanceId.substring(0, 7));

            Users user = userService.getUser(userId);
            String userName = user.getUserName();

            presentAttendanceUsers.put(userId, userName);
        }

        return presentAttendanceUsers;
    }

    /**
     * 출석코드 생성하기
     * @param lectureCode 강의코드
     * @return 생성한 출석코드 객체
     */
    @Transactional
    public ResultForm generateAttendanceToken(Long lectureCode) {
        int attendanceCode = randomNumberUtils.generateAttendanceCode();
        Lecture lecture = lectureService.getLecture(lectureCode);

        List<LectureTimeCode> lectureTimeCodeList = lecture.getLectureTimeCode();

        String week = String.valueOf(lectureWeekUtils.getWeek()); // 현재 주차
        String day = String.valueOf(LocalDateTime.now().getDayOfWeek().getValue() - 1); // 월(0) ~ 금(5)

        for (LectureTimeCode lectureTimeCode : lectureTimeCodeList) {
            if (!isCurrentLectureDay(lectureTimeCode)) {
                continue;
            }

            LocalDateTime now = LocalDateTime.now();
            LocalDateTime startDate = now.truncatedTo(ChronoUnit.MINUTES);
            LocalDateTime expirationDate = startDate.plusMinutes(3);

            if (jpaAttendanceTokenRepository.existsByLecture(lecture)) {
                AttendanceTokens findAttendanceToken = jpaAttendanceTokenRepository.findByLecture(lecture);
                int findAttendanceTokenCode = findAttendanceToken.getAttendanceCode();
                jpaAttendanceTokenRepository.deleteById(findAttendanceTokenCode);
            }

            while (jpaAttendanceTokenRepository.existsByAttendanceCode(attendanceCode)) {
                attendanceCode = randomNumberUtils.generateAttendanceCode();
            }

            AttendanceTokens attendanceToken = new AttendanceTokens(attendanceCode, lecture, startDate, expirationDate);
            jpaAttendanceTokenRepository.save(attendanceToken);

            queryRepository.setAbsent(lectureCode.toString(), day, week);

            AttendanceTokenInformation attendanceTokenInformation = conversionService.convert(attendanceToken, AttendanceTokenInformation.class);
            return ResultFormUtils.getSuccessResultForm(attendanceTokenInformation);
        }

        throw new NotAttendanceCheckTime();
    }

    /**
     * 결석 처리하기(수동)
     * @param form 유저Id, 강의 코드가 담긴 form
     */
    @Transactional
    public void setAbsent(AttendanceInputForm form) {
        Long userId = form.getUserId();
        Long lectureCode = form.getLectureCode();

        Students student = (Students) userService.getUser(userId);
        String attendanceId = generateAttendanceId(student, lectureCode);
        queryRepository.setAbsent(attendanceId);
    }

    /**
     * 지각 처리하기(수동)
     * @param form 유저Id, 강의 코드가 담긴 form
     */
    @Transactional
    public void setLateness(AttendanceInputForm form) {
        Long userId = form.getUserId();
        Long lectureCode = form.getLectureCode();

        Students student = (Students) userService.getUser(userId);
        String attendanceId = generateAttendanceId(student, lectureCode);
        queryRepository.setLateness(attendanceId);
    }

    /**
     * 출석 처리하기(수동)
     * @param form 유저Id, 강의 코드가 담긴 form
     */
    @Transactional
    public void setAttend(AttendanceInputForm form) {
        Long userId = form.getUserId();
        Long lectureCode = form.getLectureCode();

        Students student = (Students) userService.getUser(userId);
        String attendanceId = generateAttendanceId(student, lectureCode);
        queryRepository.setAttend(attendanceId);
    }

    private boolean isCurrentLectureDay(LectureTimeCode lectureTimeCode) {
        // TO-BE : 현재 날짜와 강의 날짜가 서로 같은지 확인
        String day = String.valueOf(LocalDateTime.now().getDayOfWeek().getValue() - 1); // 월(0) ~ 금(5)
        String timeCode = lectureTimeCode.getLectureTimeCode();
        String lectureDay = timeCode.substring(1, 2);
        return day.equals(lectureDay);
    }

    private ResultForm checkAndSaveAttendance(String attendanceId, LocalTime currentTime, LectureTimeCode lectureTimeCode) {
        // TO-BE : 시간을 확인하여 각 상황에 맞는 출석정보 저장
        String timeCode = lectureTimeCode.getLectureTimeCode();
        int lectureHour = Integer.parseInt(timeCode.substring(3, 5));
        int lectureMinute = Integer.parseInt(timeCode.substring(5, 7));

        LocalTime startTime = calculateStartTime(lectureHour, lectureMinute);
        LocalTime endTime = calculateEndTime(lectureHour, lectureMinute);
        LocalTime latenessStartTime = endTime.minusMinutes(1); // 출석시간 후 10분간 지각 처리, 그 이후는 결석 처리
        LocalTime latenessEndTime = endTime.plusMinutes(10);

        if (currentTime.isAfter(startTime) && currentTime.isBefore(endTime)) {
            saveAttendance(attendanceId, 1); // 출석 처리
            return ResultFormUtils.getSuccessResultForm(COMPLETE_ATTENDANCE.getDescription());
        } else if (currentTime.isAfter(latenessStartTime) && currentTime.isBefore(latenessEndTime)){
            saveAttendance(attendanceId, 2); // 지각 처리
            return ResultFormUtils.getSuccessResultForm(TREAT_LATENESS.getDescription());
        }
        throw new NotAttendanceCheckTime();
    }

    private LocalTime calculateStartTime(int lectureHour, int lectureMinute) {
        // TO-BE : 비콘 출석시작 시간 계산
        // 수업시간 시작 10분 전, 학칙에 의거 수업시간 시작 후 20분 이내이면 출석
        return lectureMinute == 0 ? LocalTime.of(lectureHour - 1, 49) : LocalTime.of(lectureHour, 19);
    }

    private LocalTime calculateEndTime(int lectureHour, int lectureMinute) {
        // TO-BE : 비콘 출석종료 시간 계산
        return lectureMinute == 0 ? LocalTime.of(lectureHour, 21) : LocalTime.of(lectureHour, 51);
    }

    private void saveAttendance(String attendanceId, int status) {
        // TO-BE : 출석정보 저장
        Attendance attendance = new Attendance(attendanceId, status);
        jpaAttendanceRepository.save(attendance);
    }

    private Map<Integer, Long> aggregateAndSortAttendanceCounts(List<Tuple> attendanceCountList){
        // TO-BE : 각 출석 상태별로 출석 횟수를 집계하고, 그 결과를 오름차순으로 정렬된 Map으로 반환
        Map<Integer, Long> attendanceCounts = new TreeMap<>();

        for (int i = 1; i < 4; i++) {
            attendanceCounts.put(i, 0L);
        }

        for (Tuple attendanceCount : attendanceCountList) {
            Integer attendanceCode = attendanceCount.get(0, Integer.class);

            if (attendanceCode == 0) {
                continue;
            }

            Long count = attendanceCount.get(1, Long.class);
            attendanceCounts.put(attendanceCode, count);
        }
        return attendanceCounts;
    }

    private AttendanceTokens getAttendanceToken(int attendanceCode) {
        if (!jpaAttendanceTokenRepository.existsByAttendanceCode(attendanceCode)) {
            throw new AttendanceCodeMismatch();
        }

        return jpaAttendanceTokenRepository.findByAttendanceCode(attendanceCode);
    }

    private boolean isAttendanceChecked(String attendanceId) {
        if (!jpaAttendanceRepository.existsByAttendanceStatus(attendanceId)) {
            return false;
        }

        return true;
    }

    private String generateAttendanceId(Students loggedInUser, Long lectureCode) {
        Long userId = loggedInUser.getUserId();
        String studentGrade = loggedInUser.getStudentGrade().substring(0, 1);
        String studentSemester = loggedInUser.getStudentSemester().substring(0, 1);

        int week = lectureWeekUtils.getWeek(); // 현재 주차
        String day = String.valueOf(LocalDateTime.now().getDayOfWeek().getValue() - 1); // 월(0) ~ 금(5)

        String attendanceId = userId.toString() + lectureCode.toString() + studentGrade + studentSemester + day + 0 + week;

        return attendanceId;
    }

    private String generateMatchingAttendanceId(Students loggedInUser, Long lectureCode) {
        Long userId = loggedInUser.getUserId();
        String studentGrade = loggedInUser.getStudentGrade().substring(0, 1);
        String studentSemester = loggedInUser.getStudentSemester().substring(0, 1);

        String attendanceId = userId.toString() + lectureCode.toString() + studentGrade + studentSemester;

        return attendanceId;
    }
}