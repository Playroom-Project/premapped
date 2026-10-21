package premapped.generation;

/** Describes durable job states; an interrupted RUNNING job requires manual Resume. */
public enum JobState {
    CREATED, RUNNING, PAUSING, PAUSED, COMPLETED, CANCELLED, FAILED
}
