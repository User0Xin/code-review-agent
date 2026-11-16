package com.agent.codereviewagent.service;

import com.agent.codereviewagent.mapper.RepositoryProfileMapper;
import com.agent.codereviewagent.mapper.RepositorySkillMapper;
import com.agent.codereviewagent.models.dto.RepositoryRequest;
import com.agent.codereviewagent.models.dto.SkillImportRequest;
import com.agent.codereviewagent.models.entity.RepositoryProfileEntity;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.core.io.ClassPathResource;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

/** 管理仓库配置、平台身份和指令。评审开始时生成不可变快照。 */
@Service
public class RepositoryProfiles {
    private static final List<String> SLOTS = List.of("review","false-positive","summary");
    private final RepositoryProfileMapper profiles;
    private final RepositorySkillMapper skills;
    private final RepositoryAccess access;

    public RepositoryProfiles(RepositoryProfileMapper profiles, RepositorySkillMapper skills, RepositoryAccess access) {
        this.profiles = profiles; this.skills = skills; this.access = access;
    }

    public List<RepositoryProfile> list() {
        return profiles.selectList(Wrappers.<RepositoryProfileEntity>lambdaQuery()
                .orderByAsc(RepositoryProfileEntity::getId)).stream().map(this::view).toList();
    }
    public Optional<RepositoryProfile> find(String platform, long remoteId) {
        return Optional.ofNullable(profiles.selectOne(Wrappers.<RepositoryProfileEntity>lambdaQuery()
                .eq(RepositoryProfileEntity::getPlatform,platform).eq(RepositoryProfileEntity::getRemoteId,remoteId))).map(this::view);
    }
    public RepositoryProfile get(long id) { return view(entity(id)); }

    public RepositoryProfile create(RepositoryRequest request) {
        var address = access.address(request.url());
        var item = new RepositoryProfileEntity();
        applyPaths(item,request);
        item.setPlatform(address.platform()); item.setFullName(address.fullName()); item.setUrl(address.url());
        // 只从受信任的平台 API 确认绑定的数字身份，不从浏览器接收。
        item.setRemoteId(access.resolve(address));
        if (profiles.register(item) != 1) { throw new IllegalArgumentException("该仓库已存在"); }
        return view(item);
    }

    @Transactional
    public RepositoryProfile update(long id, RepositoryRequest request) {
        var item = entity(id);
        var address = access.address(request.url());
        if (!address.url().equalsIgnoreCase(item.getUrl())) { throw new IllegalArgumentException("已登记仓库的身份不能修改"); }
        applyPaths(item,request);
        profiles.updateById(item);
        return view(item);
    }

    private static void applyPaths(RepositoryProfileEntity item, RepositoryRequest request) {
        item.setStandardPaths(String.join("\n",ReviewDocuments.validate(request.standardPaths())));
        item.setSpecPaths(String.join("\n",ReviewDocuments.validate(request.specPaths())));
    }

    public RepositoryProfile.Skill skill(long id,String slot) {
        entity(id);
        validateSlot(slot);
        return skills.list(id).stream().filter(s -> s.slot().equals(slot)).findFirst()
                .map(s -> new RepositoryProfile.Skill(slot,true,s.enabled(),s.content()))
                .orElseGet(() -> new RepositoryProfile.Skill(slot,false,false,defaultSkill(slot)));
    }

    @Transactional
    public RepositoryProfile storeSkill(long id,String slot,SkillImportRequest request) {
        entity(id);
        validateSlot(slot);
        var previous = skills.list(id).stream().filter(s -> s.slot().equals(slot)).findFirst();
        String content = request.content();
        if (content == null) {
            content = previous.orElseThrow(() -> new IllegalArgumentException("请先导入 SKILL.md")).content();
        }
        validateSkill(content);
        boolean enabled = request.enabled() != null ? request.enabled() : true;
        skills.store(id,slot,content,enabled);
        return get(id);
    }

    private static void validateSkill(String text) {
        if (text.isBlank() || text.indexOf('\u0000') >= 0 || text.getBytes(StandardCharsets.UTF_8).length > 65536) {
            throw new IllegalArgumentException("指令必须是非空的 UTF-8 Markdown 文本，最大 64 KiB");
        }
    }

    public RepositoryProfile removeSkill(long id,String slot) {
        entity(id); validateSlot(slot); skills.remove(id,slot); return get(id);
    }

    @Transactional(readOnly = true)
    public ReviewPolicy policy(long id) {
        var profile = get(id);
        var overrides = skills.list(id).stream().filter(RepositorySkillMapper.Entry::enabled)
                .collect(Collectors.toUnmodifiableMap(RepositorySkillMapper.Entry::slot, RepositorySkillMapper.Entry::content));
        return new ReviewPolicy(profile, overrides);
    }
    public static String defaultSkill(String slot) {
        validateSlot(slot);
        try { return new ClassPathResource("skills/" + slot + "/SKILL.md").getContentAsString(StandardCharsets.UTF_8); }
        catch (IOException unreadable) { throw new IllegalStateException("内置指令读取失败"); }
    }
    private static void validateSlot(String slot) {
        if (!SLOTS.contains(slot)) { throw new IllegalArgumentException("未知的指令类型"); }
    }
    private RepositoryProfileEntity entity(long id) {
        var entity = profiles.selectById(id);
        if (entity == null) { throw new NoSuchElementException("仓库不存在"); }
        return entity;
    }
    private RepositoryProfile view(RepositoryProfileEntity item) {
        var available = skills.list(item.getId());
        var statuses = SLOTS.stream().map(slot -> available.stream().filter(s -> s.slot().equals(slot)).findFirst()
                .map(s -> new RepositoryProfile.SkillStatus(slot,true,s.enabled()))
                .orElse(new RepositoryProfile.SkillStatus(slot,false,false))).toList();
        return new RepositoryProfile(item.getId(),item.getPlatform(),item.getRemoteId(),item.getFullName(),item.getUrl(),
                item.getStandardPaths().lines().toList(),item.getSpecPaths().lines().toList(),statuses);
    }
}
