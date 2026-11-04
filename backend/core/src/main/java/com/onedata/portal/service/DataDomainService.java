package com.onedata.portal.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.onedata.portal.entity.DataDomain;
import com.onedata.portal.mapper.DataDomainMapper;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;



/**
 * 数据域服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DataDomainService {

    private final DataDomainMapper dataDomainMapper;

    /**
     * 查询数据域列表
     */
    public List<DataDomain> list(String businessDomain) {
        LambdaQueryWrapper<DataDomain> wrapper = new LambdaQueryWrapper<DataDomain>()
            .orderByAsc(DataDomain::getDomainCode);
        if (StringUtils.hasText(businessDomain)) {
            wrapper.eq(DataDomain::getBusinessDomain, businessDomain);
        }
        return dataDomainMapper.selectList(wrapper);
    }

    /**
     * 创建数据域
     */
    @Transactional
    public DataDomain create(DataDomain domain) {
        validate(domain);
        ensureDomainCodeUnique(domain.getDomainCode(), null);
        dataDomainMapper.insert(domain);
        log.info("Created data domain: {}", domain.getDomainCode());
        return domain;
    }

    /**
     * 更新数据域
     */
    @Transactional
    public DataDomain update(Long id, DataDomain domain) {
        DataDomain exists = dataDomainMapper.selectById(id);
        if (exists == null) {
            throw new RuntimeException("数据域不存在");
        }
        validate(domain);
        ensureDomainCodeUnique(domain.getDomainCode(), id);
        domain.setId(id);
        dataDomainMapper.updateById(domain);
        log.info("Updated data domain: {}", domain.getDomainCode());
        return dataDomainMapper.selectById(id);
    }

    /**
     * 删除数据域
     */
    @Transactional
    public void delete(Long id) {
        dataDomainMapper.deleteById(id);
        log.info("Deleted data domain: {}", id);
    }

    private void validate(DataDomain domain) {
        if (domain == null) {
            throw new IllegalArgumentException("数据域不能为空");
        }
        if (!StringUtils.hasText(domain.getDomainCode())) {
            throw new RuntimeException("数据域代码不能为空");
        }
        if (!StringUtils.hasText(domain.getDomainName())) {
            throw new RuntimeException("数据域名称不能为空");
        }
    }

    private void ensureDomainCodeUnique(String domainCode, Long excludeId) {
        LambdaQueryWrapper<DataDomain> wrapper = new LambdaQueryWrapper<DataDomain>()
            .eq(DataDomain::getDomainCode, domainCode);
        if (excludeId != null) {
            wrapper.ne(DataDomain::getId, excludeId);
        }
        Long count = dataDomainMapper.selectCount(wrapper);
        if (count != null && count > 0) {
            throw new RuntimeException("数据域代码已存在: " + domainCode);
        }
    }
}
