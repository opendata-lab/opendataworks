package com.onedata.portal.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.onedata.portal.entity.BusinessDomain;
import com.onedata.portal.mapper.BusinessDomainMapper;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;



/**
 * 业务域服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BusinessDomainService {

    private final BusinessDomainMapper businessDomainMapper;

    /**
     * 查询所有业务域
     */
    public List<BusinessDomain> listAll() {
        return businessDomainMapper.selectList(
            new LambdaQueryWrapper<BusinessDomain>()
                .orderByAsc(BusinessDomain::getDomainCode)
        );
    }

    /**
     * 创建业务域
     */
    @Transactional
    public BusinessDomain create(BusinessDomain domain) {
        validate(domain);
        ensureDomainCodeUnique(domain.getDomainCode(), null);
        businessDomainMapper.insert(domain);
        log.info("Created business domain: {}", domain.getDomainCode());
        return domain;
    }

    /**
     * 更新业务域
     */
    @Transactional
    public BusinessDomain update(Long id, BusinessDomain domain) {
        BusinessDomain exists = businessDomainMapper.selectById(id);
        if (exists == null) {
            throw new RuntimeException("业务域不存在");
        }
        validate(domain);
        ensureDomainCodeUnique(domain.getDomainCode(), id);
        domain.setId(id);
        businessDomainMapper.updateById(domain);
        log.info("Updated business domain: {}", domain.getDomainCode());
        return businessDomainMapper.selectById(id);
    }

    /**
     * 删除业务域
     */
    @Transactional
    public void delete(Long id) {
        businessDomainMapper.deleteById(id);
        log.info("Deleted business domain: {}", id);
    }

    private void validate(BusinessDomain domain) {
        if (domain == null) {
            throw new IllegalArgumentException("业务域不能为空");
        }
        if (!StringUtils.hasText(domain.getDomainCode())) {
            throw new RuntimeException("业务域代码不能为空");
        }
        if (!StringUtils.hasText(domain.getDomainName())) {
            throw new RuntimeException("业务域名称不能为空");
        }
    }

    private void ensureDomainCodeUnique(String domainCode, Long excludeId) {
        LambdaQueryWrapper<BusinessDomain> wrapper = new LambdaQueryWrapper<BusinessDomain>()
            .eq(BusinessDomain::getDomainCode, domainCode);
        if (excludeId != null) {
            wrapper.ne(BusinessDomain::getId, excludeId);
        }
        Long count = businessDomainMapper.selectCount(wrapper);
        if (count != null && count > 0) {
            throw new RuntimeException("业务域代码已存在: " + domainCode);
        }
    }
}
