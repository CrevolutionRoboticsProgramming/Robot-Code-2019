package org.frc2851.robot.subsystems;

import badlog.lib.BadLog;
import com.ctre.phoenix.ErrorCode;
import com.ctre.phoenix.motion.SetValueMotionProfile;
import com.ctre.phoenix.motorcontrol.*;
import com.ctre.phoenix.motorcontrol.can.TalonSRX;
import com.ctre.phoenix.motorcontrol.can.WPI_TalonSRX;
import com.ctre.phoenix.sensors.PigeonIMU;
import edu.wpi.first.wpilibj.DoubleSolenoid;
import edu.wpi.first.wpilibj.PneumaticsModuleType;
import edu.wpi.first.wpilibj.Timer;
import org.frc2851.crevolib.io.Axis;
import org.frc2851.crevolib.io.Button;
import org.frc2851.crevolib.io.Controller;
import org.frc2851.crevolib.motion.MotionProfileExecutor;
import org.frc2851.crevolib.motion.PID;
import org.frc2851.crevolib.subsystem.Command;
import org.frc2851.crevolib.subsystem.Subsystem;
import org.frc2851.crevolib.utilities.Logger;
import org.frc2851.crevolib.utilities.TalonCommunicationErrorException;
import org.frc2851.crevolib.utilities.TalonSRXFactory;
import org.frc2851.robot.Constants;
import org.frc2851.robot.Robot;

import java.util.ArrayList;
import java.util.Arrays;

// Left and right encoders are flipped on purpose; electrical did it wrong.

/**
 * Represents the drivetrain subsystem
 */
public class DriveTrain extends Subsystem
{
    private static boolean mTuneMode = false;
    private static DriveTrain mInstance;
    // Constants
    private final NeutralMode TALON_NEUTRAL_MODE = NeutralMode.Brake;
    // PID
    private PID mLeftMotionPID, mRightMotionPID, mAuxMotionPID, mLeftRawPID, mRightRawPID, mTurnGyroPID;
    private int mMotionSlotPrimary = 0, mMotionSlotAux = 1, mRawSlotDrive = 2, mRawSlotTurn = 3;
    // Members
    private DriveControlMode mDriveControlMode = DriveControlMode.TANK;
    private WPI_TalonSRX mLeftMaster, mLeftSlaveA, mLeftSlaveB, mRightMaster, mRightSlaveA, mRightSlaveB;
    private ArrayList<TalonSRX> leftMotors = new ArrayList<>(), rightMotors = new ArrayList<>();
    private PigeonIMU mPigeon;
    private DoubleSolenoid mShifterSolenoid;
    private Controller mController = Constants.driver;
    private Constants mConstants = Constants.getInstance();
    private DriveGear mCurrentGear = mConstants.dt_defaultDriveGear;
    private DriveTrainControlMode mControlMode = DriveTrainControlMode.OPEN_LOOP;

    /**
     * Initializes the DriveTrain class with the name "DriveTrain"
     */
    private DriveTrain()
    {
        super("DriveTrain");
    }

    /**
     * Returns the sole instance of the DriveTrain class
     *
     * @return The instance of the DriveTrain class
     */
    public static DriveTrain getInstance()
    {
        if (mInstance == null) mInstance = new DriveTrain();
        return mInstance;
    }

    /**
     * Initializes the controllers, motor controllers, and logging
     *
     * @return A boolean representing whether initialization has succeeded
     */
    @Override
    protected boolean init()
    {
        configureController(Constants.driver);

        Constants.operator.config(Button.ButtonID.LEFT_TRIGGER, Button.ButtonMode.RAW);

        try
        {
            mLeftMaster = TalonSRXFactory.createDefaultMasterWPI_TalonSRX(mConstants.dt_leftMaster);
            mLeftSlaveA = TalonSRXFactory.createPermanentSlaveWPI_TalonSRX(mConstants.dt_leftSlaveA, mLeftMaster);
            mLeftSlaveB = TalonSRXFactory.createPermanentSlaveWPI_TalonSRX(mConstants.dt_leftSlaveB, mLeftMaster);
            mRightMaster = TalonSRXFactory.createDefaultMasterWPI_TalonSRX(mConstants.dt_rightMaster);
            mRightSlaveA = TalonSRXFactory.createPermanentSlaveWPI_TalonSRX(mConstants.dt_rightSlaveA, mRightMaster);
            mRightSlaveB = TalonSRXFactory.createPermanentSlaveWPI_TalonSRX(mConstants.dt_rightSlaveB, mRightMaster);

            leftMotors.add(mLeftMaster);
            leftMotors.add(mLeftSlaveA);
            leftMotors.add(mLeftSlaveB);

            rightMotors.add(mRightMaster);
            rightMotors.add(mRightSlaveA);
            rightMotors.add(mRightSlaveB);

            for (TalonSRX talon : leftMotors) talon.setInverted(true);
            for (TalonSRX talon : rightMotors) talon.setInverted(true);
        } catch (TalonCommunicationErrorException e)
        {
            log("Could not initialize motor, drivetrain init failed! Port: " + e.getPortNumber(), Logger.LogLevel.ERROR);
            return false;
        }

        setNominalAndPeakOutputs(mConstants.dt_nominalOut, mConstants.dt_peakOut);
        setNeutralMode(NeutralMode.Brake);

        mShifterSolenoid = new DoubleSolenoid(PneumaticsModuleType.CTREPCM, mConstants.dt_shifterForwardChannel, mConstants.dt_shifterReverseChannel);

        return true;
    }


    /**
     * Resets the motor controllers and encoders
     */
    private void reset()
    {
        if (!zeroSensors()) log("Failed to zero sensors", Logger.LogLevel.ERROR);
        setLeftRightMotorOutputs(0, 0);
        setNeutralMode(mConstants.dt_defaultNeutralMode);
    }

    /**
     * Zeroes all sensors
     *
     * @return A boolean representing whether zeroing has succeeded
     */
    private boolean zeroSensors()
    {
        return TalonSRXFactory.runTalonConfig(
                () -> mLeftMaster.getSensorCollection().setQuadraturePosition(0, mConstants.talonTimeout),
                () -> mRightMaster.getSensorCollection().setQuadraturePosition(0, mConstants.talonTimeout)
        );
    }

    /**
     * Returns a Command representing user control over the drivetrain
     *
     * @return A Command representing user control over the drivetrain
     */
    @Override
    public Command getDefaultCommand()
    {
        return new Command()
        {
            @Override
            public String getName()
            {
                return "Teleop";
            }

            @Override
            public boolean isFinished()
            {
                return false;
            }

            @Override
            public boolean init()
            {
                reset();

                mLeftMaster.configSelectedFeedbackSensor(FeedbackDevice.CTRE_MagEncoder_Relative, 0, mConstants.talonTimeout);
                mRightMaster.configSelectedFeedbackSensor(FeedbackDevice.CTRE_MagEncoder_Relative, 0, mConstants.talonTimeout);
                mLeftMaster.setSensorPhase(false);
                mRightMaster.setSensorPhase(true);

                return true;
            }

            @Override
            public void update()
            {
                if (Robot.isRunning())
                {
                    boolean gearToggle = mController.get(mConstants.dt_gearToggle);
                    double throttle = mController.get(Axis.AxisID.LEFT_Y);
                    double rotation = mController.get(Axis.AxisID.RIGHT_X);

                    DriveGear requestedGear = (gearToggle) ? DriveGear.HIGH : DriveGear.LOW;
                    if (requestedGear != mCurrentGear)
                    {
                        mShifterSolenoid.set(requestedGear.val);
                        mCurrentGear = requestedGear;
                    }

                    arcadeDrive(throttle, rotation, mConstants.dt_turnMult);
                }
            }

            @Override
            public void stop()
            {
                reset();
            }

            void arcadeDrive(double throttle, double turn, double turnMult)
            {
                turn *= turnMult;
                double leftOut, rightOut;
                double maxInput = Math.copySign(Math.max(Math.abs(throttle), Math.abs(turn)), throttle);

                if (throttle >= 0.0)
                {
                    // First quadrant, else second quadrant
                    if (turn >= 0.0)
                    {
                        leftOut = maxInput;
                        rightOut = throttle - turn;
                    } else
                    {
                        leftOut = throttle + turn;
                        rightOut = maxInput;
                    }
                } else
                {
                    // Third quadrant, else fourth quadrant
                    if (turn >= 0.0)
                    {
                        leftOut = throttle + turn;
                        rightOut = maxInput;
                    } else
                    {
                        leftOut = maxInput;
                        rightOut = throttle - turn;
                    }
                }

                mLeftMaster.set(ControlMode.PercentOutput, leftOut);
                mRightMaster.set(ControlMode.PercentOutput, -rightOut);
            }
        };
    }

    /**
     * Returns a Command making the robot drive for the specified time
     *
     * @param time       The time in seconds to drive for
     * @param leftPower  The power to drive the left motors at
     * @param rightPower The power to drive the right motors at
     * @return A Command making the robot drive for the specified time
     */
    public Command driveTime(double time, double leftPower, double rightPower)
    {
        return new Command()
        {
            double startTime;

            @Override
            public String getName()
            {
                return "DriveTime[T: " + time + ", L: " + leftPower + ", R: " + rightPower + "]";
            }

            @Override
            public boolean isFinished()
            {
                return (Timer.getFPGATimestamp() - startTime) > time;
            }

            @Override
            public boolean init()
            {
                startTime = Timer.getFPGATimestamp();
                return true;
            }

            @Override
            public void update()
            {
                mLeftMaster.set(ControlMode.PercentOutput, leftPower);
                mRightMaster.set(ControlMode.PercentOutput, rightPower);
            }

            @Override
            public void stop()
            {
                mLeftMaster.set(ControlMode.PercentOutput, 0);
                mRightMaster.set(ControlMode.PercentOutput, 0);
            }
        };
    }

    /**
     * Returns a Command making the robot drive for the specified time
     *
     * @param time  The time in seconds to drive for
     * @param power The power to drive at
     * @return A Command making the robot drive for the specified time
     */
    public Command driveTime(double time, double power)
    {
        return driveTime(time, power, power);
    }

    public Command driveDistance(double distance)
    {
        return new Command()
        {
            final double UNITS_PER_ROTATION = 10000.0d;
            // Scales the encoder difference reading to 3600 counts per rotation
            final double turnCoeff = 3600.0d / UNITS_PER_ROTATION;
            double targetAngle;
            double targetPos;

            @Override
            public String getName()
            {
                return "DriveDistanceMotionProfile[" + distance + "]";
            }

            @Override
            public boolean isFinished()
            {
                return Math.abs(targetPos - mRightMaster.getSelectedSensorPosition(0)) < 1024;
            }

            @Override
            public boolean init()
            {
                double startTime = Timer.getFPGATimestamp();
                // Sensor Config
                mRightMaster.configSelectedFeedbackSensor(FeedbackDevice.CTRE_MagEncoder_Relative, 0, mConstants.talonTimeout);

                mLeftMaster.configRemoteFeedbackFilter(mRightMaster.getDeviceID(), RemoteSensorSource.TalonSRX_SelectedSensor,
                        0, mConstants.talonTimeout);
                mLeftMaster.configSensorTerm(SensorTerm.Sum0, FeedbackDevice.RemoteSensor0, mConstants.talonTimeout);
                mLeftMaster.configSensorTerm(SensorTerm.Sum1, FeedbackDevice.CTRE_MagEncoder_Relative, mConstants.talonTimeout);
                mLeftMaster.configSelectedFeedbackSensor(FeedbackDevice.SensorSum, 0, mConstants.talonTimeout);
                mLeftMaster.configSelectedFeedbackCoefficient(0.5, 0, mConstants.talonTimeout);

                mLeftMaster.selectProfileSlot(mConstants.dt_drivePidSlot, 0);

                if (mConstants.dt_usePigeon)
                {
                    mLeftMaster.configRemoteFeedbackFilter(mPigeon.getDeviceID(), RemoteSensorSource.Pigeon_Yaw,
                            1, mConstants.talonTimeout);
                    mLeftMaster.configSelectedFeedbackSensor(FeedbackDevice.RemoteSensor1, 1, mConstants.talonTimeout);
                    mLeftMaster.configSelectedFeedbackCoefficient(3600.0d / 8192.0d, 1, mConstants.talonTimeout);

                    mLeftMaster.selectProfileSlot(mConstants.dt_gyroPidSlot, 1);
                } else
                {
                    mLeftMaster.configSensorTerm(SensorTerm.Diff0, FeedbackDevice.CTRE_MagEncoder_Relative, mConstants.talonTimeout);
                    mLeftMaster.configSensorTerm(SensorTerm.Diff1, FeedbackDevice.RemoteSensor1, mConstants.talonTimeout);
                    mLeftMaster.configSelectedFeedbackSensor(FeedbackDevice.SensorDifference, 1, mConstants.talonTimeout);
                    mLeftMaster.configSelectedFeedbackCoefficient(turnCoeff, 1, mConstants.talonTimeout);

                    mLeftMaster.selectProfileSlot(mConstants.dt_encTurnPidSlot, 1);
                }

                mLeftMaster.configAuxPIDPolarity(false, mConstants.talonTimeout);

                // Motion Magic Config
                mLeftMaster.configMotionAcceleration(mConstants.dt_motionMagicMaxAcceleration, mConstants.talonTimeout);
                mLeftMaster.configMotionCruiseVelocity(mConstants.dt_motionMagicMaxVelocity, mConstants.talonTimeout);

                targetAngle = mLeftMaster.getSelectedSensorPosition(1);
                targetPos = distanceToCounts(distance) + mLeftMaster.getSelectedSensorPosition(0);
                Logger.println("Time for MMG Init: " + (Timer.getFPGATimestamp() - startTime), Logger.LogLevel.DEBUG);
                return true;
            }

            @Override
            public void update()
            {
                mLeftMaster.set(ControlMode.MotionMagic, targetPos, DemandType.AuxPID, targetAngle);
                mRightMaster.follow(mLeftMaster, FollowerType.AuxOutput1);
            }

            @Override
            public void stop()
            {
                reset();
            }
        };
    }

    /**
     * Returns a Command making the robot rotate to the specified angle using its encoders
     *
     * @param angle  The angle to turn to
     * @param maxOut The maximum output (0.0 to 1.0) of the motors
     * @return A Command making the robot rotate to the specified angle using its encoders
     */
    public Command turnToAngleEncoder(double angle, double maxOut)
    {
        return new Command()
        {
            //              v For arc length
            // angle (rads) * radius of wheelbase * ticks per inch (ticks per rotation / circumference of wheel (pi * diameter))
            int counts = (int) (((Math.toRadians(angle) * mConstants.dt_width) * 0.5) / (mConstants.dt_wheelDiameter * Math.PI) * mConstants.dt_countsPerRotation);
            int targetPos = 0;

            @Override
            public String getName()
            {
                return "TurnAngle[" + angle + "]";
            }

            @Override
            public boolean isFinished()
            {
                return Math.abs(Math.abs(mRightMaster/*mLeftMaster*/.getSelectedSensorPosition(0)) - Math.abs(targetPos)) < 32;//mLeftMaster.getClosedLoopError(0) < 512;
            }

            @Override
            public boolean init()
            {
                targetPos = (int) mRightMaster/*mLeftMaster*/.getSelectedSensorPosition(0) + counts;
                log("Target: " + targetPos, Logger.LogLevel.DEBUG);
                TalonSRXFactory.configurePIDF(mRightMaster/*mLeftMaster*/, 0, mLeftRawPID);
                TalonSRXFactory.configurePIDF(mLeftMaster/*mRightMaster*/, 0, mRightRawPID);
                mRightMaster/*mLeftMaster*/.set(ControlMode.Position, targetPos);

                setNominalAndPeakOutputs(0, maxOut);
                mControlMode = DriveTrainControlMode.CLOSED_LOOP;
                return true;
            }

            @Override
            public void update()
            {
                mRightMaster/*mLeftMaster*/.set(ControlMode.Position, targetPos);
                mLeftMaster/*mRightMaster*/.set(ControlMode.PercentOutput, mRightMaster/*mLeftMaster*/.getMotorOutputPercent());
            }

            @Override
            public void stop()
            {
                reset();
                setNominalAndPeakOutputs(mConstants.dt_nominalOut, mConstants.dt_peakOut);
                mControlMode = DriveTrainControlMode.OPEN_LOOP;
            }
        };
    }


    /**
     * Returns a Command that runs the motion profile supplied to it
     *
     * @param leftProfile  The file name of the motion profile to execute corresponding with the left side of the drivetrain
     * @param rightProfile The file name of the motion profile to execute corresponding with the right side of the drivetrain
     * @return
     */
    public Command runMotionProfile(String leftProfile, String rightProfile)
    {
        return new Command()
        {
            MotionProfileExecutor left, right;
            boolean profileNull = false;

            @Override
            public String getName()
            {
                return "MotionProfile[" + leftProfile + ", " + rightProfile + "]";
            }

            @Override
            public boolean isFinished()
            {
                // TODO: Double check that the flag is triggering
                SetValueMotionProfile leftSet, rightSet;
                leftSet = left.getSetValue();
                rightSet = right.getSetValue();

                return (leftSet == SetValueMotionProfile.Hold && rightSet == SetValueMotionProfile.Hold);
            }

            @Override
            public boolean init()
            {
                try
                {
                    left = new MotionProfileExecutor(Robot.getMotionProfile(leftProfile), mLeftMaster, false);
                    right = new MotionProfileExecutor(Robot.getMotionProfile(rightProfile), mRightMaster, false);
                } catch (NullPointerException e)
                {
                    return false;
                }

                TalonSRXFactory.configurePIDF(mLeftMaster, 0, mLeftMotionPID);
                TalonSRXFactory.configurePIDF(mRightMaster, 0, mRightMotionPID);

                //TODO: Feedback Coeff
                mLeftMaster.configSelectedFeedbackSensor(FeedbackDevice.CTRE_MagEncoder_Relative, 0, mConstants.talonTimeout);
                mRightMaster.configSelectedFeedbackSensor(FeedbackDevice.CTRE_MagEncoder_Relative, 0, mConstants.talonTimeout);
                if (mConstants.dt_usePigeon)
                {
                    mLeftMaster.configRemoteFeedbackFilter(mPigeon.getDeviceID(), RemoteSensorSource.Pigeon_Yaw, 0, mConstants.talonTimeout);
                    mLeftMaster.configSelectedFeedbackSensor(FeedbackDevice.RemoteSensor0, 1, mConstants.talonTimeout);

                    mRightMaster.configRemoteFeedbackFilter(mPigeon.getDeviceID(), RemoteSensorSource.Pigeon_Yaw, 0, mConstants.talonTimeout);
                    mRightMaster.configSelectedFeedbackSensor(FeedbackDevice.RemoteSensor0, 1, mConstants.talonTimeout);
                }

                left.start();
                right.start();
                return true;
            }

            @Override
            public void update()
            {
                left.update();
                right.update();

                mLeftMaster.set(ControlMode.MotionProfile, left.getSetValue().value);
                mRightMaster.set(ControlMode.MotionProfile, right.getSetValue().value);
            }

            @Override
            public void stop()
            {
                left.reset();
                right.reset();
                reset();
            }
        };
    }

    /**
     * Returns a Command that sets the drive gear of the drivetrain
     *
     * @param gear The gear to set the drivetrain to
     * @return A Command that sets the drive gear of the drivetrain
     */
    public Command setDriveGear(DriveGear gear)
    {
        return new Command()
        {
            @Override
            public String getName()
            {
                return "SetDriveGear[" + gear.name() + "]";
            }

            @Override
            public boolean isFinished()
            {
                return true;
            }

            @Override
            public boolean init()
            {
                mShifterSolenoid.set(gear.val);
                mCurrentGear = gear;
                return true;
            }

            @Override
            public void update()
            {
            }

            @Override
            public void stop()
            {
            }
        };
    }

    /**
     * Sets the nominal and peak outputs of the motors to the specified configuration
     *
     * @param nominal The nominal output to set
     * @param peak    The peak output to set
     * @return A boolean indicating whether configuration has succeeded or not
     */
    private boolean setNominalAndPeakOutputs(double nominal, double peak)
    {
        boolean setsSucceeded = true;
        int tries = 0;
        final int maxRetries = 5;
        do
        {
            for (var motorGroup : Arrays.asList(leftMotors, rightMotors)) {
                for (var motor : motorGroup) {
                    setsSucceeded &= motor.configNominalOutputForward(nominal, mConstants.talonTimeout) == ErrorCode.OK;
                    setsSucceeded &= motor.configPeakOutputForward(peak, mConstants.talonTimeout) == ErrorCode.OK;
                    setsSucceeded &= motor.configNominalOutputReverse(-nominal, mConstants.talonTimeout) == ErrorCode.OK;
                    setsSucceeded &= motor.configPeakOutputReverse(-peak, mConstants.talonTimeout) == ErrorCode.OK;
                }
            }
        } while (!setsSucceeded && tries++ < maxRetries);
        return setsSucceeded;
    }

    /**
     * Sets the neutral mode of the master Talons
     *
     * @param mode The mode to set the master Talons to
     */
    public void setNeutralMode(NeutralMode mode) {
        for (var motorGroup : Arrays.asList(leftMotors, rightMotors)) {
            for (var motor : motorGroup) {
                motor.setNeutralMode(mode);
            }
        }
    }

    /**
     * Sets the outputs to the left and right motors
     *
     * @param left  The percent output to supply to the left set of motors (-1.0 to 1.0)
     * @param right The percent output to supply to the right set of motors (-1.0 to 1.0)
     */
    private void setLeftRightMotorOutputs(double left, double right) {
        mLeftMaster.set(ControlMode.PercentOutput, left);
        mRightMaster.set(ControlMode.PercentOutput, right);
    }

    /**
     * Configures the controller with the axes and buttons used to control the DriveTrain subsystem
     *
     * @param controller The controller to configure
     */
    private void configureController(Controller controller)
    {
        controller.config(Axis.AxisID.LEFT_Y, x -> applyDeadband(-x, 0.1)); // Throttle
//        controller.config(Axis.AxisID.LEFT_X, x -> applyDeadband(x, 0.1)); // Throttle
//        controller.config(Axis.AxisID.RIGHT_Y, x -> applyDeadband(-x, 0.1)); // Throttle
        controller.config(Axis.AxisID.RIGHT_X, x -> applyDeadband(x, 0.1)); // Turn
//        controller.config(mConstants.dt_curvatureToggle, Button.ButtonMode.TOGGLE); // Curvature
        controller.config(mConstants.dt_gearToggle, Button.ButtonMode.TOGGLE); // Shifter
        controller.config(Button.ButtonID.X, Button.ButtonMode.RAW);
    }

    /**
     * Converts the distance in inches given to encoder ticks
     *
     * @param distance
     * @return
     */
    private int distanceToCounts(double distance)
    {
        return (int) (distance / (mConstants.magEncCPR * mConstants.dt_wheelDiameter * Math.PI));
    }

    /**
     * Applies a deadband to the given value
     *
     * @param val      The value to apply the deadband to
     * @param deadband The deadband to apply to the value
     * @return The new value as modified by the deadband
     */
    private double applyDeadband(double val, double deadband)
    {
        return (Math.abs(val) < deadband) ? 0 : val;
    }

    /**
     * Converts the velocity supplied by CTRE devices (in counts per 100ms) to feet per second
     *
     * @param ctreVel The velocity (in counts per 100ms)
     * @return The equivalent feet per second of the given velocity
     */
    private double ctreVelToFPS(int ctreVel)
    {
        return ((ctreVel * 10) / (double) (mConstants.magEncCPR)) * mConstants.dt_wheelDiameter * Math.PI;
    }

    private enum DriveTrainControlMode
    {
        OPEN_LOOP, CLOSED_LOOP
    }

    public enum DriveGear
    {
        HIGH(DoubleSolenoid.Value.kForward), LOW(DoubleSolenoid.Value.kReverse);

        private final DoubleSolenoid.Value val;

        DriveGear(DoubleSolenoid.Value val)
        {
            this.val = val;
        }
    }

    public enum DriveControlMode
    {
        TANK, FPS, FPS_CURVE, ARCADE
    }
}