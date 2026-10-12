<template>
  <div class="domain-management" v-loading="loading">
    <el-alert
      v-if="isDemoMode"
      :title="demoReadonlyMessage"
      type="info"
      show-icon
      :closable="false"
      class="readonly-alert"
    />

    <el-row :gutter="20">
      <el-col :span="12">
        <el-card shadow="never" class="card">
          <template #header>
            <div class="card-header">
              业务域
              <el-button type="primary" size="small" :disabled="isDemoMode" @click="openBusinessDialog()">
                <el-icon><Plus /></el-icon>
                新建
              </el-button>
            </div>
          </template>

          <el-table :data="businessDomains" border>
            <el-table-column prop="domainCode" label="代码" width="140" />
            <el-table-column prop="domainName" label="名称" />
            <el-table-column prop="description" label="描述" />
            <el-table-column label="操作" width="150">
              <template #default="{ row }">
                <el-button link type="primary" :disabled="isDemoMode" @click="openBusinessDialog(row)">编辑</el-button>
                <el-popconfirm title="确定删除该业务域吗?" :disabled="isDemoMode" @confirm="removeBusiness(row.id)">
                  <template #reference>
                    <el-button link type="danger" :disabled="isDemoMode">删除</el-button>
                  </template>
                </el-popconfirm>
              </template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-col>

      <el-col :span="12">
        <el-card shadow="never" class="card">
          <template #header>
            <div class="card-header">
              数据域
              <div class="card-actions">
                <el-select
                  v-model="dataDomainFilter"
                  placeholder="按业务域筛选"
                  clearable
                  size="small"
                  @change="loadDataDomains"
                >
                  <el-option
                    v-for="item in businessDomains"
                    :key="item.domainCode"
                    :label="item.domainName"
                    :value="item.domainCode"
                  />
                </el-select>
                <el-button type="primary" size="small" :disabled="isDemoMode" @click="openDataDialog()">
                  <el-icon><Plus /></el-icon>
                  新建
                </el-button>
              </div>
            </div>
          </template>

          <el-table :data="dataDomains" border>
            <el-table-column prop="domainCode" label="代码" width="140" />
            <el-table-column prop="domainName" label="名称" />
            <el-table-column prop="businessDomain" label="所属业务域" width="160">
              <template #default="{ row }">
                {{ findBusinessName(row.businessDomain) }}
              </template>
            </el-table-column>
            <el-table-column prop="description" label="描述" />
            <el-table-column label="操作" width="150">
              <template #default="{ row }">
                <el-button link type="primary" :disabled="isDemoMode" @click="openDataDialog(row)">编辑</el-button>
                <el-popconfirm title="确定删除该数据域吗?" :disabled="isDemoMode" @confirm="removeDataDomain(row.id)">
                  <template #reference>
                    <el-button link type="danger" :disabled="isDemoMode">删除</el-button>
                  </template>
                </el-popconfirm>
              </template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-col>
    </el-row>

    <!-- 业务域表单 -->
    <el-dialog
      v-model="businessDialogVisible"
      :title="businessForm.id ? '编辑业务域' : '新建业务域'"
      width="500px"
      @close="resetBusinessForm"
    >
      <el-form ref="businessFormRef" :model="businessForm" :rules="businessRules" label-width="100px">
        <el-form-item label="代码" prop="domainCode">
          <el-input v-model="businessForm.domainCode" :disabled="!!businessForm.id" placeholder="如: tech" />
        </el-form-item>
        <el-form-item label="名称" prop="domainName">
          <el-input v-model="businessForm.domainName" placeholder="业务域名称" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="businessForm.description" type="textarea" :rows="3" placeholder="可选" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="businessDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="savingBusiness" @click="submitBusiness">确定</el-button>
      </template>
    </el-dialog>

    <!-- 数据域表单 -->
    <el-dialog
      v-model="dataDialogVisible"
      :title="dataForm.id ? '编辑数据域' : '新建数据域'"
      width="520px"
      @close="resetDataForm"
    >
      <el-form ref="dataFormRef" :model="dataForm" :rules="dataRules" label-width="110px">
        <el-form-item label="代码" prop="domainCode">
          <el-input v-model="dataForm.domainCode" :disabled="!!dataForm.id" placeholder="如: ops" />
        </el-form-item>
        <el-form-item label="名称" prop="domainName">
          <el-input v-model="dataForm.domainName" placeholder="数据域名称" />
        </el-form-item>
        <el-form-item label="所属业务域" prop="businessDomain">
          <el-select v-model="dataForm.businessDomain" placeholder="选择业务域">
            <el-option
              v-for="item in businessDomains"
              :key="item.domainCode"
              :label="item.domainName"
              :value="item.domainCode"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="dataForm.description" type="textarea" :rows="3" placeholder="可选" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dataDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="savingData" @click="submitDataDomain">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'
import { businessDomainApi, dataDomainApi } from '@/api/domain'
import { demoReadonlyMessage, isDemoMode, showDemoReadonlyMessage } from '@/demo/runtime'

const loading = ref(false)

const businessDomains = ref([])
const dataDomains = ref([])
const dataDomainFilter = ref('')

const businessDialogVisible = ref(false)
const dataDialogVisible = ref(false)

const businessFormRef = ref(null)
const dataFormRef = ref(null)
const savingBusiness = ref(false)
const savingData = ref(false)

const businessForm = reactive({
  id: null,
  domainCode: '',
  domainName: '',
  description: ''
})

const dataForm = reactive({
  id: null,
  domainCode: '',
  domainName: '',
  businessDomain: '',
  description: ''
})

const businessRules = {
  domainCode: [{ required: true, message: '请输入业务域代码', trigger: 'blur' }],
  domainName: [{ required: true, message: '请输入业务域名称', trigger: 'blur' }]
}

const dataRules = {
  domainCode: [{ required: true, message: '请输入数据域代码', trigger: 'blur' }],
  domainName: [{ required: true, message: '请输入数据域名称', trigger: 'blur' }],
  businessDomain: [{ required: true, message: '请选择所属业务域', trigger: 'change' }]
}

const loadBusinessDomains = async () => {
  businessDomains.value = await businessDomainApi.list()
}

const loadDataDomains = async () => {
  const params = {}
  if (dataDomainFilter.value) {
    params.businessDomain = dataDomainFilter.value
  }
  dataDomains.value = await dataDomainApi.list(params)
}

const openBusinessDialog = (row) => {
  if (isDemoMode) {
    showDemoReadonlyMessage(row ? '编辑业务域' : '新建业务域')
    return
  }
  resetBusinessForm()
  if (row) {
    Object.assign(businessForm, row)
  }
  businessDialogVisible.value = true
}

const openDataDialog = (row) => {
  if (isDemoMode) {
    showDemoReadonlyMessage(row ? '编辑数据域' : '新建数据域')
    return
  }
  resetDataForm()
  if (row) {
    Object.assign(dataForm, row)
  } else if (dataDomainFilter.value) {
    dataForm.businessDomain = dataDomainFilter.value
  }
  dataDialogVisible.value = true
}

const resetBusinessForm = () => {
  businessFormRef.value?.resetFields()
  businessForm.id = null
  businessForm.domainCode = ''
  businessForm.domainName = ''
  businessForm.description = ''
}

const resetDataForm = () => {
  dataFormRef.value?.resetFields()
  dataForm.id = null
  dataForm.domainCode = ''
  dataForm.domainName = ''
  dataForm.businessDomain = ''
  dataForm.description = ''
}

const submitBusiness = async () => {
  if (isDemoMode) {
    showDemoReadonlyMessage('保存业务域')
    return
  }
  await businessFormRef.value.validate()
  savingBusiness.value = true
  try {
    if (businessForm.id) {
      await businessDomainApi.update(businessForm.id, businessForm)
      ElMessage.success('更新业务域成功')
    } else {
      await businessDomainApi.create(businessForm)
      ElMessage.success('创建业务域成功')
    }
    businessDialogVisible.value = false
    await loadBusinessDomains()
    await loadDataDomains()
  } catch (error) {
    console.error('保存业务域失败', error)
  } finally {
    savingBusiness.value = false
  }
}

const submitDataDomain = async () => {
  if (isDemoMode) {
    showDemoReadonlyMessage('保存数据域')
    return
  }
  await dataFormRef.value.validate()
  savingData.value = true
  try {
    if (dataForm.id) {
      await dataDomainApi.update(dataForm.id, dataForm)
      ElMessage.success('更新数据域成功')
    } else {
      await dataDomainApi.create(dataForm)
      ElMessage.success('创建数据域成功')
    }
    dataDialogVisible.value = false
    await loadDataDomains()
  } catch (error) {
    console.error('保存数据域失败', error)
  } finally {
    savingData.value = false
  }
}

const removeBusiness = async (id) => {
  if (isDemoMode) {
    showDemoReadonlyMessage('删除业务域')
    return
  }
  try {
    await businessDomainApi.remove(id)
    ElMessage.success('删除业务域成功')
    await loadBusinessDomains()
    await loadDataDomains()
  } catch (error) {
    console.error('删除业务域失败', error)
  }
}

const removeDataDomain = async (id) => {
  if (isDemoMode) {
    showDemoReadonlyMessage('删除数据域')
    return
  }
  try {
    await dataDomainApi.remove(id)
    ElMessage.success('删除数据域成功')
    await loadDataDomains()
  } catch (error) {
    console.error('删除数据域失败', error)
  }
}

const findBusinessName = (code) => {
  const match = businessDomains.value.find(item => item.domainCode === code)
  return match ? match.domainName : code || '-'
}

onMounted(async () => {
  loading.value = true
  try {
    await loadBusinessDomains()
    await loadDataDomains()
  } finally {
    loading.value = false
  }
})
</script>

<style scoped>
.domain-management {
  padding: 6px;
}

.page-header {
  margin-bottom: 12px;
}

.readonly-alert {
  margin-bottom: 12px;
}

.card {
  margin-bottom: 12px;
  border-radius: 12px;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.08);
}

.card :deep(.el-card__body) {
  padding: 16px;
}

.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-weight: 600;
}

.card-actions {
  display: flex;
  align-items: center;
  gap: 10px;
}
</style>
