/*
 * Copyright (C) 2017 Axel Müller <axel.mueller@avanux.de>
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program; if not, write to the Free Software Foundation, Inc.,
 * 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA.
 */
package de.avanux.smartapplianceenabler.appliance;

import com.pi4j.io.gpio.GpioController;
import de.avanux.smartapplianceenabler.control.*;
import de.avanux.smartapplianceenabler.meter.*;
import de.avanux.smartapplianceenabler.modbus.ModbusSlave;
import de.avanux.smartapplianceenabler.modbus.ModbusTcp;
import de.avanux.smartapplianceenabler.schedule.*;
import org.joda.time.DateTime;
import org.joda.time.Interval;
import org.joda.time.LocalDate;
import org.joda.time.LocalDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.xml.bind.annotation.*;
import java.util.*;

@XmlRootElement
@XmlAccessorType(XmlAccessType.FIELD)
public class Appliance implements ControlStateChangedListener, StartingCurrentSwitchListener {
    private transient Logger logger = LoggerFactory.getLogger(Appliance.class);
    @XmlAttribute
    private String id;
    @XmlElements({
            @XmlElement(name = "HttpElectricityMeter", type = HttpElectricityMeter.class),
            @XmlElement(name = "ModbusElectricityMeter", type = ModbusElectricityMeter.class),
            @XmlElement(name = "S0ElectricityMeter", type = S0ElectricityMeter.class),
            @XmlElement(name = "S0ElectricityMeterNetworked", type = S0ElectricityMeterNetworked.class)
    })
    private Meter meter;
    // Mapping interfaces in JAXB:
    // https://jaxb.java.net/guide/Mapping_interfaces.html
    // http://stackoverflow.com/questions/25374375/jaxb-wont-unmarshal-my-previously-marshalled-interface-impls
    @XmlElements({
            @XmlElement(name = "AlwaysOnSwitch", type = AlwaysOnSwitch.class),
            @XmlElement(name = "HttpSwitch", type = HttpSwitch.class),
            @XmlElement(name = "MockSwitch", type = MockSwitch.class),
            @XmlElement(name = "ModbusSwitch", type = ModbusSwitch.class),
            @XmlElement(name = "StartingCurrentSwitch", type = StartingCurrentSwitch.class),
            @XmlElement(name = "Switch", type = Switch.class)
    })
    private Control control;
    @XmlElement(name = "Schedule")
    private List<Schedule> schedules;
    private transient RunningTimeMonitor runningTimeMonitor;

    public void setId(String id) {
        this.id = id;
    }

    public String getId() {
        return id;
    }

    public Meter getMeter() {
        return this.meter;
    }

    public void setMeter(Meter meter) {
        this.meter = meter;
    }

    public Control getControl() {
        return control;
    }

    public void setControl(Control control) {
        this.control = control;
    }

    public List<Schedule> getSchedules() {
        return schedules;
    }

    public void setSchedules(List<Schedule> schedules) {
        this.schedules = schedules;
    }

    public RunningTimeMonitor getRunningTimeMonitor() {
        return runningTimeMonitor;
    }

    public void setRunningTimeMonitor(RunningTimeMonitor runningTimeMonitor) {
        this.runningTimeMonitor = runningTimeMonitor;
        this.runningTimeMonitor.setApplianceId(id);
    }

    public void init(Integer additionRunningTime) {
        if(control != null) {
            runningTimeMonitor = new RunningTimeMonitor();
            runningTimeMonitor.setApplianceId(id);
        }
        if(schedules != null && schedules.size() > 0) {
            logger.info("{}: Schedules configured: {}", id, schedules.size());
            if(! hasStartingCurrentDetection()) {
                // in case of starting current detection timeframes are added after
                // starting current was detected
                if(runningTimeMonitor != null) {
                    runningTimeMonitor.setSchedules(schedules);
                    logger.debug("{}: Schedules passed to RunningTimeMonitor", id);
                }
            }
        }
        else {
            logger.info("{}: No schedules configured", id);
        }

        if(control != null) {
            if(control instanceof ApplianceIdConsumer) {
                ((ApplianceIdConsumer) control).setApplianceId(id);
            }
            if(control instanceof StartingCurrentSwitch) {
                Control wrappedControl = ((StartingCurrentSwitch) control).getControl();
                ((ApplianceIdConsumer) wrappedControl).setApplianceId(id);
                wrappedControl.addControlStateChangedListener(this);
                logger.debug("{}: Registered as {} with {}", id, ControlStateChangedListener.class.getSimpleName(),
                        wrappedControl.getClass().getSimpleName());
                ((StartingCurrentSwitch) control).addStartingCurrentSwitchListener(this);
                logger.debug("{}: Registered as {} with {}", id, StartingCurrentSwitchListener.class.getSimpleName(),
                        control.getClass().getSimpleName());
            }
            else {
                control.addControlStateChangedListener(this);
                logger.debug("{}: Registered as {} with {}", id, ControlStateChangedListener.class.getSimpleName(),
                        control.getClass().getSimpleName());
            }
        }
        Meter meter = getMeter();
        if(meter != null) {
            if(meter instanceof ApplianceIdConsumer) {
                ((ApplianceIdConsumer) meter).setApplianceId(id);
            }
            if(control != null) {
                if(meter instanceof S0ElectricityMeter) {
                    ((S0ElectricityMeter) meter).setControl(control);
                }
                if(meter instanceof S0ElectricityMeterNetworked) {
                    ((S0ElectricityMeterNetworked) meter).setControl(control);
                }
                logger.debug("{}: {} uses {}", id, meter.getClass().getSimpleName(), control.getClass().getSimpleName());
            }
        }

        if(schedules != null) {
            if(additionRunningTime != null) {
                Schedule.setAdditionalRunningTime(additionRunningTime);
            }
            for(Schedule schedule : schedules) {
                schedule.getTimeframe().setSchedule(schedule);
            }
        }
    }

    public void start(Timer timer, GpioController gpioController,
                      Map<String, PulseReceiver> pulseReceiverIdWithPulseReceiver,
                      Map<String, ModbusTcp> modbusIdWithModbusTcp) {

        if(runningTimeMonitor != null) {
            runningTimeMonitor.setTimer(timer);
        }

        for(GpioControllable gpioControllable : getGpioControllables()) {
            logger.info("{}: Starting {}", id, gpioControllable.getClass().getSimpleName());
            gpioControllable.setGpioController(gpioController);
            gpioControllable.start();
        }

        if(meter != null && meter instanceof S0ElectricityMeterNetworked) {
            S0ElectricityMeterNetworked s0ElectricityMeterNetworked = (S0ElectricityMeterNetworked) meter;
            logger.info("{}: Starting {}", id, S0ElectricityMeterNetworked.class.getSimpleName());
            String pulseReceiverId = s0ElectricityMeterNetworked.getIdref();
            PulseReceiver pulseReceiver = pulseReceiverIdWithPulseReceiver.get(pulseReceiverId);
            s0ElectricityMeterNetworked.setPulseReceiver(pulseReceiver);
            s0ElectricityMeterNetworked.start();
        }

        if(meter != null && meter instanceof HttpElectricityMeter) {
            ((HttpElectricityMeter) meter).start(timer);
        }

        for(ModbusSlave modbusSlave : getModbusSlaves()) {
            logger.info("{}: Starting {}", id, modbusSlave.getClass().getSimpleName());
            modbusSlave.setApplianceId(id);
            String modbusId = modbusSlave.getIdref();
            ModbusTcp modbusTcp = modbusIdWithModbusTcp.get(modbusId);
            modbusSlave.setModbusTcp(modbusTcp);
        }
        if(meter != null && meter instanceof ModbusElectricityMeter) {
            ((ModbusElectricityMeter) meter).start(timer);
        }

        if(control != null && control instanceof  StartingCurrentSwitch) {
            logger.info("{}: Starting {}", id, StartingCurrentSwitch.class.getSimpleName());
            ((StartingCurrentSwitch) control).start(getMeter(), timer);
        }
    }

    public void setHolidays(List<LocalDate> holidays) {
        if(schedules != null) {
            for(Schedule schedule : schedules) {
                final Timeframe timeframe = schedule.getTimeframe();
                if(timeframe instanceof DayTimeframe) {
                    ((DayTimeframe) timeframe).setHolidays(holidays);
                }
            }
        }
    }

    public boolean hasTimeframeForHolidays() {
        if(schedules != null) {
            for (Schedule schedule : schedules) {
                Timeframe timeframe = schedule.getTimeframe();
                if(timeframe instanceof  DayTimeframe) {
                    List<Integer> daysOfWeekValues = ((DayTimeframe) timeframe).getDaysOfWeekValues();
                    if(daysOfWeekValues != null && daysOfWeekValues.contains(DayTimeframe.DOW_HOLIDAYS)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private Set<GpioControllable> getGpioControllables() {
        Set<GpioControllable> controllables = new HashSet<GpioControllable>();
        if(meter != null && meter instanceof S0ElectricityMeter) {
            controllables.add((S0ElectricityMeter) meter);
        }
        if(control != null) {
            if(control instanceof  GpioControllable) {
                controllables.add((GpioControllable) control);
            }
            else if(control instanceof  StartingCurrentSwitch) {
                Control wrappedControl = ((StartingCurrentSwitch) control).getControl();
                if(wrappedControl instanceof GpioControllable) {
                    controllables.add((GpioControllable) wrappedControl);
                }
            }
        }
        return controllables;
    }

    public boolean isControllable() {
        return control != null &&
            (
                control instanceof Switch
                || control instanceof HttpSwitch
                || control instanceof ModbusSwitch
                || control instanceof StartingCurrentSwitch
            );
    }

    public void setApplianceState(boolean switchOn, String logMessage) {
        if(control != null) {
            boolean stateChanged = false;
            // only change state if requested state differs from actual state
            if(control.isOn() ^ switchOn) {
                control.on(switchOn);
                stateChanged = true;
            }
            if(stateChanged) {
                logger.debug(logMessage);
            }
        }
        else {
            logger.warn("{}: Appliance configuration does not contain control.", id);
        }
    }

    private Set<ModbusSlave> getModbusSlaves() {
        Set<ModbusSlave> slaves = new HashSet<ModbusSlave>();
        if(meter != null && meter instanceof  ModbusElectricityMeter) {
            slaves.add((ModbusElectricityMeter) meter);
        }
        if(control != null) {
            if(control instanceof  ModbusSwitch) {
                slaves.add((ModbusSwitch) control);
            }
            else if(control instanceof  StartingCurrentSwitch) {
                Control wrappedControl = ((StartingCurrentSwitch) control).getControl();
                if(wrappedControl instanceof ModbusSwitch) {
                    slaves.add((ModbusSwitch) wrappedControl);
                }
             }
        }
        return slaves;
    }

    public boolean canConsumeOptionalEnergy() {
        if(schedules != null) {
            for(Schedule schedule : schedules) {
                if(schedule.getMaxRunningTime() != schedule.getMinRunningTime()) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean hasStartingCurrentDetection() {
        return control != null && control instanceof StartingCurrentSwitch;
    }

    /**
     * Returns a forced schedule if there is one.
     * @param now
     * @return
     */
    private Schedule getForcedSchedule(LocalDateTime now) {
        String scheduleId = null;
        if(control != null) {
            if(control instanceof StartingCurrentSwitch) {
                DayTimeframeCondition dayTimeframeCondition = ((StartingCurrentSwitch) control).getDayTimeframeCondition();
                if(dayTimeframeCondition != null) {
                    if(dayTimeframeCondition.isMet(now)) {
                        scheduleId = dayTimeframeCondition.getIdref();
                    }
                }
            }
        }
        if(scheduleId != null) {
            for(Schedule schedule : schedules) {
                if(scheduleId.equals(schedule.getId())) {
                    return schedule;
                }
            }
        }
        return null;
    }

    public List<RuntimeRequest> getRuntimeRequests(LocalDateTime now) {
        List<RuntimeRequest> runtimeRequests = new ArrayList<>();
        if(runningTimeMonitor != null) {
            runtimeRequests = getRuntimeRequests(now,
                    runningTimeMonitor.getSchedules(),
                    runningTimeMonitor.getActiveTimeframeInterval(now),
                    runningTimeMonitor.getRemainingMinRunningTimeOfCurrentTimeFrame(now),
                    runningTimeMonitor.getRemainingMaxRunningTimeOfCurrentTimeFrame(now));
        }
        return runtimeRequests;
    }

    protected List<RuntimeRequest> getRuntimeRequests(LocalDateTime now, List<Schedule> schedules,
                                                      TimeframeInterval activeTimeframeInterval,
                                                      int remainingMinRunningTime, int remainingMaxRunningTime) {
        List<RuntimeRequest> runtimeRequests = new ArrayList<>();
        if(schedules != null && schedules.size() > 0) {
            logger.debug("Active schedules: " + schedules.size());
            addRuntimeRequest(now, activeTimeframeInterval, runtimeRequests, remainingMinRunningTime, remainingMaxRunningTime);

            Interval considerationInterval = new Interval(now.toDateTime(), now.plusDays(2).toDateTime());
            List<TimeframeInterval> timeFrameIntervals = Schedule.findTimeframeIntervals(now, considerationInterval, schedules);
            for(TimeframeInterval timeframeIntervalOfSchedule : timeFrameIntervals) {
                Schedule schedule = timeframeIntervalOfSchedule.getTimeframe().getSchedule();
                addRuntimeRequest(runtimeRequests, timeframeIntervalOfSchedule.getInterval(), schedule.getMinRunningTime(),
                        schedule.getMaxRunningTime(), now);
            }
        }
        else if(activeTimeframeInterval != null) {
            logger.debug("Active timeframe interval found");
            addRuntimeRequest(now, activeTimeframeInterval, runtimeRequests, remainingMinRunningTime, remainingMaxRunningTime);
        }
        else {
            logger.debug("No timeframes found");
        }
        return runtimeRequests;
    }

    private void addRuntimeRequest(LocalDateTime now, TimeframeInterval timeframeInterval, List<RuntimeRequest> runtimeRequests,
                                   int remainingMinRunningTime, int remainingMaxRunningTime) {
        if(timeframeInterval != null) {
            addRuntimeRequest(runtimeRequests, timeframeInterval.getInterval(),
                    remainingMinRunningTime, remainingMaxRunningTime, now);
            if(remainingMaxRunningTime < 0) {
                setApplianceState(false, "Switching off due to maxRunningTime < 0");
            }
        }
    }

    private void addRuntimeRequest(List<RuntimeRequest> runtimeRequests, Interval interval, long remainingMinRunningTime,
                                   long remainingMaxRunningTime, LocalDateTime now) {
        runtimeRequests.add(createRuntimeRequest(interval, remainingMinRunningTime, remainingMaxRunningTime, now));
    }

    protected RuntimeRequest createRuntimeRequest(Interval interval, long minRunningTime,
                                                  long maxRunningTime, LocalDateTime now) {
        Long earliestStart = 0l;
        DateTime start = interval.getStart();
        DateTime end = interval.getEnd();
        if(start.isAfter(now.toDateTime())) {
            earliestStart = Double.valueOf(new Interval(now.toDateTime(), start).toDurationMillis() / 1000).longValue();
        }
        LocalDateTime nowBeforeEnd = new LocalDateTime(now);
        if(now.toDateTime().isAfter(end)) {
            nowBeforeEnd = now.minusHours(24);
        }
        Long latestEnd = Double.valueOf(new Interval(nowBeforeEnd.toDateTime(), end).toDurationMillis() / 1000).longValue();
        return createRuntimeRequest(earliestStart, latestEnd, minRunningTime, maxRunningTime);
    }

    protected RuntimeRequest createRuntimeRequest(Long earliestStart, Long latestEnd, long minRunningTime,
                                                  long maxRunningTime) {
        RuntimeRequest runtimeRequest = new RuntimeRequest();
        runtimeRequest.setEarliestStart(earliestStart);
        runtimeRequest.setLatestEnd(latestEnd);
        if(minRunningTime == maxRunningTime) {
            /** WORKAROUND:
             * For unknown reason the SunnyPortal displays the scheduled times only
             * if maxRunningTime AND minRunningTime are returned and are NOT EQUAL
             * Therefore we ensure that they are not equal by reducing minRunningTime by 1 second
             */
            runtimeRequest.setMinRunningTime(minRunningTime >= 1 ? minRunningTime - 1 : 0);
        }
        else {
            // according to spec minRunningTime only has to be returned if different from maxRunningTime
            runtimeRequest.setMinRunningTime(minRunningTime >= 0 ? minRunningTime : 0);
        }
        runtimeRequest.setMaxRunningTime(maxRunningTime >= 0 ? maxRunningTime : 0);
        logger.debug("RuntimeRequest created: " + runtimeRequest);
        return runtimeRequest;
    }

    @Override
    public void controlStateChanged(boolean switchOn) {
        logger.debug("{}: Control state has changed to {}", id, (switchOn ? "on" : "off"));
        if(runningTimeMonitor != null) {
            runningTimeMonitor.setRunning(switchOn);
        }
    }

    @Override
    public void startingCurrentDetected() {
        logger.debug("{}: Activating next sufficient timeframe interval after starting current has been detected", id);
        LocalDateTime now = new LocalDateTime();
        TimeframeInterval timeframeInterval;
        Schedule forcedSchedule = getForcedSchedule(now);
        if(forcedSchedule != null) {
            logger.debug("{}: Forcing schedule {}", id, forcedSchedule);
            timeframeInterval = Schedule.getCurrentOrNextTimeframeInterval(now, Collections.singletonList(forcedSchedule), false, true);
        }
        else {
            timeframeInterval = Schedule.getCurrentOrNextTimeframeInterval(now, schedules, false, true);
        }
        runningTimeMonitor.activateTimeframeInterval(now, timeframeInterval);
    }

    @Override
    public void finishedCurrentDetected() {
        logger.debug("{}: Deactivating timeframe interval until starting current is detected again", id);
        if(runningTimeMonitor != null) {
            runningTimeMonitor.activateTimeframeInterval(new LocalDateTime(), (TimeframeInterval) null);
        }
    }
}
