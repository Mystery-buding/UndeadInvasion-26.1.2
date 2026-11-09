package override_mirage.undeadinvasion.gameplay.ai;

/** 将骷髅的武器目标重建合并到本轮 AI 结束，避免修改正在遍历的目标集合。 */
public interface DeferredWeaponGoalUpdate {
    void undeadinvasion$beginAiStep();

    void undeadinvasion$endAiStep();
}
