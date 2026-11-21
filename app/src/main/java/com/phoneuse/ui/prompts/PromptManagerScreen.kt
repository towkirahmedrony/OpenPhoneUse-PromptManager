package com.phoneuse.ui.prompts

import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phoneuse.agent.AgentPrompt
import com.phoneuse.agent.Personalization
import com.phoneuse.i18n.tr
import com.phoneuse.prompt.PromptDefaults
import com.phoneuse.prompt.PromptLibrary
import com.phoneuse.prompt.PromptProfile
import com.phoneuse.prompt.PromptProfiles
import com.phoneuse.prompt.PromptSection
import com.phoneuse.prompt.PromptStore
import com.phoneuse.prompt.PromptTransfer
import com.phoneuse.prompt.SystemPrompt
import com.phoneuse.ui.theme.ButtonKind
import com.phoneuse.ui.theme.IconBtn
import com.phoneuse.ui.theme.MonoStyle
import com.phoneuse.ui.theme.PlexSans
import com.phoneuse.ui.theme.PuButton
import com.phoneuse.ui.theme.PuIcons
import com.phoneuse.ui.theme.PuRadio
import com.phoneuse.ui.theme.PuSwitch
import com.phoneuse.ui.theme.pu
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * System Prompt Manager: the prompts that head every AI request.
 *
 * Everything is local (one DataStore file), nothing is uploaded, and the protected security layer is never part
 * of an editable prompt, so an edit, an import or a profile switch can change the assistant's behavior but cannot
 * weaken the confirmation flow, the password hand-off or the "never claim unverified success" rule.
 */
@Composable
fun PromptManagerScreen(
    store: PromptStore,
    personalization: Personalization,
    memoryOn: Boolean,
    autoRemember: Boolean,
    webSearch: Boolean,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = pu
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var library by remember { mutableStateOf<PromptLibrary?>(null) }
    var draft by remember { mutableStateOf<Draft?>(null) }
    var discardOpen by remember { mutableStateOf(false) }
    var previewOpen by remember { mutableStateOf(false) }
    var profileDialogOpen by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<SystemPrompt?>(null) }
    var restoreProfile by remember { mutableStateOf<PromptProfile?>(null) }
    var deleteProfileTarget by remember { mutableStateOf<PromptProfile?>(null) }
    var importTarget by remember { mutableStateOf<SystemPrompt?>(null) }
    var exportTarget by remember { mutableStateOf<SystemPrompt?>(null) }

    LaunchedEffect(store) {
        try {
            store.library.collect { library = it }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            library = PromptDefaults.library()
        }
    }

    fun toast(message: String) = Toast.makeText(context, message, Toast.LENGTH_SHORT).show()

    // System back follows the same path as the header button: never lose unsaved edits silently.
    BackHandler {
        when {
            draft?.dirty == true -> discardOpen = true
            draft != null -> draft = null
            else -> onBack()
        }
    }

    fun save(entry: Draft, onSaved: () -> Unit = {}) {
        val name = entry.name.trim()
        if (name.isEmpty()) {
            toast(tr("Give the prompt a name", "프롬프트 이름을 넣어 주세요")); return
        }
        if (entry.content.isBlank()) {
            toast(tr("The prompt is empty", "프롬프트 내용이 비어 있어요")); return
        }
        val now = System.currentTimeMillis()
        val prompt = SystemPrompt(
            id = entry.original?.id ?: PromptDefaults.newId("prompt", now),
            profileId = entry.profileId,
            name = name,
            description = entry.description.trim(),
            content = entry.content,
            enabled = entry.original?.enabled ?: true,
            isDefault = entry.original?.isDefault ?: false,
            createdAt = entry.original?.createdAt ?: now,
            updatedAt = now,
        )
        scope.launch {
            try {
                store.save(prompt)
                toast(tr("Saved", "저장했어요"))
                draft = null
                onSaved()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast(tr("Couldn't save the prompt", "프롬프트를 저장하지 못했어요"))
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val target = uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            try {
                val text = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(target)?.bufferedReader()?.use { it.readText() }
                }
                if (text.isNullOrBlank()) {
                    toast(tr("That file is empty", "파일이 비어 있어요"))
                } else {
                    val profileId = library?.activeProfileId ?: PromptProfiles.GENERAL
                    importTarget = PromptTransfer.importPrompt(text, fileName(context, target), profileId, System.currentTimeMillis())
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast(tr("Couldn't read the file", "파일을 읽지 못했어요"))
            }
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        val target = exportTarget
        exportTarget = null
        if (uri == null || target == null) return@rememberLauncherForActivityResult
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(PromptTransfer.exportText(target)) }
                }
                toast(tr("Saved the prompt file", "프롬프트 파일을 저장했어요"))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast(tr("Couldn't save the file", "파일을 저장하지 못했어요"))
            }
        }
    }

    fun export(prompt: SystemPrompt) {
        exportTarget = prompt
        exportLauncher.launch(safeFileName(prompt.name))
    }

    val loaded = library
    val currentProfile = loaded?.profile(loaded.activeProfileId)

    Column(
        modifier
            .fillMaxSize()
            .background(c.bg)
            .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars).only(WindowInsetsSides.Bottom)),
    ) {
        Row(
            Modifier.fillMaxWidth().height(56.dp).padding(start = 6.dp, end = 6.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconBtn(PuIcons.Back, tr("Back", "뒤로"), { if (draft?.dirty == true) discardOpen = true else if (draft != null) draft = null else onBack() })
            Text(
                tr("System Prompt Manager", "시스템 프롬프트 관리"),
                Modifier.weight(1f),
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = c.text,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.width(44.dp))
        }

        if (loaded == null || currentProfile == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(tr("Loading…", "불러오는 중…"), fontSize = 14.sp, color = c.textSub)
            }
            return@Column
        }

        val entry = draft
        if (entry != null) {
            PromptEditor(
                draft = entry,
                onChange = { draft = it },
                onSave = { save(entry) },
                onCancel = { if (entry.dirty) discardOpen = true else draft = null },
                modifier = Modifier.weight(1f),
            )
        } else {
            PromptList(
                library = loaded,
                profile = currentProfile,
                onSelectProfile = { profile -> scope.launch { store.setActiveProfile(profile.id) } },
                onAddProfile = { profileDialogOpen = true },
                onDeleteProfile = { profile -> deleteProfileTarget = profile },
                onNewPrompt = {
                    val now = System.currentTimeMillis()
                    draft = Draft(
                        original = null,
                        profileId = currentProfile.id,
                        name = "",
                        description = "",
                        content = "",
                        createdAt = now,
                    )
                },
                onEdit = { prompt ->
                    draft = Draft(
                        original = prompt,
                        profileId = prompt.profileId,
                        name = prompt.name,
                        description = prompt.description,
                        content = prompt.content,
                        createdAt = prompt.createdAt,
                    )
                },
                onToggle = { prompt, enabled ->
                    scope.launch {
                        try {
                            store.setEnabled(prompt.id, enabled)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            toast(tr("Couldn't change the prompt", "프롬프트를 바꾸지 못했어요"))
                        }
                    }
                },
                onActivate = { prompt ->
                    scope.launch {
                        try {
                            store.setActive(prompt.profileId, prompt.id)
                            toast(tr("Active prompt: ${prompt.name}", "활성 프롬프트: ${prompt.name}"))
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            toast(tr("Couldn't select the prompt", "프롬프트를 선택하지 못했어요"))
                        }
                    }
                },
                onDefault = { prompt -> scope.launch { store.setDefault(prompt.profileId, prompt.id) } },
                onDuplicate = { prompt ->
                    scope.launch {
                        try {
                            store.duplicate(prompt.id)
                            toast(tr("Duplicated", "복제했어요"))
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            toast(tr("Couldn't duplicate the prompt", "프롬프트를 복제하지 못했어요"))
                        }
                    }
                },
                onDelete = { prompt -> deleteTarget = prompt },
                onExport = { prompt -> export(prompt) },
                onCopy = { prompt ->
                    copyText(context, prompt.content)
                    toast(tr("Copied", "복사했어요"))
                },
                onPreview = { previewOpen = true },
                onImport = { importLauncher.launch(arrayOf("text/plain", "text/*", "application/octet-stream")) },
                onRestore = { restoreProfile = currentProfile },
                modifier = Modifier.weight(1f),
            )
        }
    }

    if (previewOpen && loaded != null) {
        EffectivePreview(
            sections = AgentPrompt.sections(personalization, memoryOn, autoRemember, webSearch, loaded.activePrompt()?.content),
            onCopy = { text ->
                copyText(context, text)
                toast(tr("Copied the full prompt", "전체 프롬프트를 복사했어요"))
            },
            onDismiss = { previewOpen = false },
        )
    }

    if (discardOpen) {
        AlertDialog(
            onDismissRequest = { discardOpen = false },
            containerColor = c.surface,
            shape = RoundedCornerShape(22.dp),
            title = { Text(tr("Discard changes?", "변경 내용을 버릴까요?"), fontSize = 18.sp, fontWeight = FontWeight.Bold, color = c.text) },
            text = { Text(tr("Your edits to this prompt have not been saved.", "이 프롬프트의 수정 내용이 저장되지 않았어요."), fontSize = 14.sp, lineHeight = 20.sp, color = c.textSub) },
            confirmButton = {
                TextButton(onClick = {
                    discardOpen = false
                    draft = null
                }) { Text(tr("Discard", "버리기"), color = c.danger, fontWeight = FontWeight.SemiBold, fontSize = 15.sp) }
            },
            dismissButton = {
                TextButton(onClick = { discardOpen = false }) { Text(tr("Keep editing", "계속 수정"), color = c.text, fontSize = 15.sp) }
            },
        )
    }

    deleteTarget?.let { prompt ->
        ConfirmDialog(
            title = tr("Delete this prompt?", "이 프롬프트를 삭제할까요?"),
            body = tr("\"${prompt.name}\" will be deleted. This can't be undone.", "\"${prompt.name}\" 프롬프트가 삭제돼요. 되돌릴 수 없어요."),
            confirm = tr("Delete", "삭제"),
            danger = true,
            onConfirm = {
                deleteTarget = null
                scope.launch {
                    try {
                        store.delete(prompt.id)
                        toast(tr("Deleted", "삭제했어요"))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        toast(tr("Couldn't delete the prompt", "프롬프트를 삭제하지 못했어요"))
                    }
                }
            },
            onDismiss = { deleteTarget = null },
        )
    }

    restoreProfile?.let { profile ->
        ConfirmDialog(
            title = tr("Restore the default prompt?", "기본 프롬프트로 되돌릴까요?"),
            body = tr(
                "The \"${profile.name}\" profile's default prompt will be restored with its original text. Your other prompts stay.",
                "\"${profile.name}\" 프로필의 기본 프롬프트를 원래 내용으로 되돌려요. 다른 프롬프트는 그대로 남아요.",
            ),
            confirm = tr("Restore", "되돌리기"),
            danger = false,
            onConfirm = {
                restoreProfile = null
                scope.launch {
                    try {
                        store.restoreDefault(profile.id)
                        toast(tr("Default prompt restored", "기본 프롬프트로 되돌렸어요"))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        toast(tr("Couldn't restore the prompt", "프롬프트를 되돌리지 못했어요"))
                    }
                }
            },
            onDismiss = { restoreProfile = null },
        )
    }

    deleteProfileTarget?.let { profile ->
        ConfirmDialog(
            title = tr("Delete this profile?", "이 프로필을 삭제할까요?"),
            body = tr(
                "\"${profile.name}\" and every prompt saved in it will be deleted. This can't be undone.",
                "\"${profile.name}\" 프로필과 그 안의 프롬프트가 모두 삭제돼요. 되돌릴 수 없어요.",
            ),
            confirm = tr("Delete", "삭제"),
            danger = true,
            onConfirm = {
                deleteProfileTarget = null
                scope.launch {
                    try {
                        store.deleteProfile(profile.id)
                        toast(tr("Profile deleted", "프로필을 삭제했어요"))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        toast(tr("Couldn't delete the profile", "프로필을 삭제하지 못했어요"))
                    }
                }
            },
            onDismiss = { deleteProfileTarget = null },
        )
    }

    importTarget?.let { prompt ->
        ConfirmDialog(
            title = tr("Import this prompt?", "이 프롬프트를 가져올까요?"),
            body = tr(
                "\"${prompt.name}\" will be added to this profile with ${prompt.content.length} characters. Existing prompts are not changed.",
                "\"${prompt.name}\" 프롬프트가 ${prompt.content.length}자로 이 프로필에 추가돼요. 기존 프롬프트는 바뀌지 않아요.",
            ),
            confirm = tr("Import", "가져오기"),
            danger = false,
            onConfirm = {
                importTarget = null
                scope.launch {
                    try {
                        store.create(prompt)
                        toast(tr("Imported", "가져왔어요"))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        toast(tr("Couldn't import the prompt", "프롬프트를 가져오지 못했어요"))
                    }
                }
            },
            onDismiss = { importTarget = null },
        )
    }

    if (profileDialogOpen) {
        NewProfileDialog(
            onDismiss = { profileDialogOpen = false },
            onCreate = { name ->
                profileDialogOpen = false
                val now = System.currentTimeMillis()
                val profileId = PromptDefaults.newId("profile", now)
                scope.launch {
                    try {
                        store.update { lib -> lib.addProfile(name, now, profileId, PromptDefaults.newId("prompt", now)) }
                        store.setActiveProfile(profileId)
                        toast(tr("Profile added", "프로필을 추가했어요"))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        toast(tr("Couldn't add the profile", "프로필을 추가하지 못했어요"))
                    }
                }
            },
        )
    }
}

/** The list of prompts of one profile. */
@Composable
private fun PromptList(
    library: PromptLibrary,
    profile: PromptProfile,
    onSelectProfile: (PromptProfile) -> Unit,
    onAddProfile: () -> Unit,
    onDeleteProfile: (PromptProfile) -> Unit,
    onNewPrompt: () -> Unit,
    onEdit: (SystemPrompt) -> Unit,
    onToggle: (SystemPrompt, Boolean) -> Unit,
    onActivate: (SystemPrompt) -> Unit,
    onDefault: (SystemPrompt) -> Unit,
    onDuplicate: (SystemPrompt) -> Unit,
    onDelete: (SystemPrompt) -> Unit,
    onExport: (SystemPrompt) -> Unit,
    onCopy: (SystemPrompt) -> Unit,
    onPreview: () -> Unit,
    onImport: () -> Unit,
    onRestore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = pu
    val active = library.activePrompt(profile.id)
    val prompts = library.promptsOf(profile.id)

    Column(
        modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 28.dp),
    ) {
        Text(
            tr("The active prompt leads every AI request, followed by protected security and agent instructions you can review but not edit.", "활성 프롬프트가 모든 AI 요청의 맨 앞에 들어가고, 그 뒤에 보호된 보안 · 에이전트 지침이 붙어요. 이 지침은 볼 수만 있고 고칠 수는 없어요."),
            fontSize = 12.5.sp,
            lineHeight = 18.sp,
            color = c.textSub,
        )

        Spacer(Modifier.height(14.dp))
        Text(tr("PROFILE", "프로필"), fontSize = 11.5.sp, fontWeight = FontWeight.Bold, color = c.textSub, letterSpacing = 1.sp)
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for (candidate in library.profiles) {
                ProfileChip(
                    name = candidate.name,
                    selected = candidate.id == profile.id,
                    onClick = { onSelectProfile(candidate) },
                    onDelete = if (!candidate.builtIn) ({ onDeleteProfile(candidate) }) else null,
                )
            }
            Box(
                Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(50))
                    .border(1.dp, c.inputBorder, RoundedCornerShape(50))
                    .clickable(role = Role.Button, onClick = onAddProfile)
                    .semantics { contentDescription = tr("Add a profile", "프로필 추가") },
                contentAlignment = Alignment.Center,
            ) {
                androidx.compose.material3.Icon(PuIcons.Plus, null, tint = c.text, modifier = Modifier.size(16.dp))
            }
        }

        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PuButton(tr("New prompt", "새 프롬프트"), onNewPrompt, Modifier.weight(1f), height = 44.dp, radius = 12.dp, fontSize = 14)
            PuButton(tr("Preview", "미리보기"), onPreview, Modifier.weight(1f), kind = ButtonKind.OUTLINE, height = 44.dp, radius = 12.dp, fontSize = 14)
        }

        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                tr("ACTIVE: ", "활성: ") + (active?.name ?: tr("none", "없음")),
                Modifier.weight(1f),
                fontSize = 12.sp,
                color = c.accentText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                tr("${prompts.count { it.enabled }} enabled", "${prompts.count { it.enabled }}개 사용 중"),
                fontSize = 12.sp,
                color = c.textSub,
            )
        }

        Spacer(Modifier.height(10.dp))
        for (prompt in prompts) {
            PromptCard(
                prompt = prompt,
                active = prompt.id == active?.id,
                onEdit = { onEdit(prompt) },
                onToggle = { enabled -> onToggle(prompt, enabled) },
                onActivate = { onActivate(prompt) },
                onDefault = { onDefault(prompt) },
                onDuplicate = { onDuplicate(prompt) },
                onDelete = { onDelete(prompt) },
                onExport = { onExport(prompt) },
                onCopy = { onCopy(prompt) },
            )
            Spacer(Modifier.height(10.dp))
        }

        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PuButton(tr("Import .txt", "TXT 가져오기"), onImport, Modifier.weight(1f), kind = ButtonKind.OUTLINE, height = 42.dp, radius = 12.dp, fontSize = 13)
            PuButton(tr("Restore default", "기본값 복원"), onRestore, Modifier.weight(1f), kind = ButtonKind.OUTLINE, height = 42.dp, radius = 12.dp, fontSize = 13)
        }
        Spacer(Modifier.height(12.dp))
        Text(
            tr("Prompts are stored on this device only. They are never uploaded to a developer server, and switching AI provider keeps them.", "프롬프트는 이 기기에만 저장돼요. 개발자 서버로 올라가지 않고, AI 제공자를 바꿔도 그대로 남아요."),
            fontSize = 12.sp,
            lineHeight = 17.sp,
            color = c.textSub,
        )
    }
}

@Composable
private fun ProfileChip(name: String, selected: Boolean, onClick: () -> Unit, onDelete: (() -> Unit)?) {
    val c = pu
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) c.primary else c.surface)
            .border(1.dp, if (selected) c.primary else c.inputBorder, RoundedCornerShape(50))
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(start = 14.dp, end = if (onDelete == null) 14.dp else 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            name,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            color = if (selected) c.onPrimary else c.text,
            maxLines = 1,
        )
        if (onDelete != null) {
            Spacer(Modifier.width(6.dp))
            Box(
                Modifier
                    .size(20.dp)
                    .clip(RoundedCornerShape(50))
                    .clickable(role = Role.Button, onClick = onDelete)
                    .semantics { contentDescription = tr("Delete profile $name", "프로필 $name 삭제") },
                contentAlignment = Alignment.Center,
            ) {
                androidx.compose.material3.Icon(PuIcons.Close, null, tint = if (selected) c.onPrimary else c.textSub, modifier = Modifier.size(12.dp))
            }
        }
    }
}

@Composable
private fun PromptCard(
    prompt: SystemPrompt,
    active: Boolean,
    onEdit: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onActivate: () -> Unit,
    onDefault: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
    onExport: () -> Unit,
    onCopy: () -> Unit,
) {
    val c = pu
    var menu by remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(c.surface)
            .border(1.dp, if (active) c.accentBorder else c.line, RoundedCornerShape(16.dp))
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(50))
                    .clickable(enabled = prompt.enabled, role = Role.RadioButton, onClick = onActivate)
                    .semantics { contentDescription = tr("Make ${prompt.name} active", "${prompt.name} 활성화") },
                contentAlignment = Alignment.Center,
            ) {
                PuRadio(active)
            }
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f).clickable(role = Role.Button, onClick = onEdit)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        prompt.name,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = c.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (prompt.isDefault) {
                        Spacer(Modifier.width(6.dp))
                        Tag(tr("DEFAULT", "기본"))
                    }
                    if (active) {
                        Spacer(Modifier.width(6.dp))
                        Tag(tr("ACTIVE", "활성"), accent = true)
                    }
                }
                if (prompt.description.isNotBlank()) {
                    Spacer(Modifier.height(3.dp))
                    Text(prompt.description, fontSize = 12.5.sp, lineHeight = 17.sp, color = c.textSub, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    tr("${prompt.content.length} characters · updated ${shortDate(prompt.updatedAt)}", "${prompt.content.length}자 · ${shortDate(prompt.updatedAt)} 수정"),
                    style = MonoStyle,
                    fontSize = 11.sp,
                    color = c.textSub,
                )
            }
            Spacer(Modifier.width(8.dp))
            PuSwitch(prompt.enabled, onToggle, tr("Enable ${prompt.name}", "${prompt.name} 사용"))
            Box {
                IconBtn(PuIcons.More, tr("More actions", "더 보기"), { menu = true }, tint = c.textSub)
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    Item(tr("Edit", "편집")) { menu = false; onEdit() }
                    Item(tr("Duplicate", "복제")) { menu = false; onDuplicate() }
                    Item(tr("Make active", "활성으로 지정")) { menu = false; onActivate() }
                    Item(tr("Set as default", "기본으로 지정")) { menu = false; onDefault() }
                    Item(tr("Copy content", "내용 복사")) { menu = false; onCopy() }
                    Item(tr("Export .txt", "TXT로 내보내기")) { menu = false; onExport() }
                    if (!prompt.isDefault) {
                        Item(tr("Delete", "삭제"), danger = true) { menu = false; onDelete() }
                    }
                }
            }
        }
    }
}

@Composable
private fun Tag(text: String, accent: Boolean = false) {
    val c = pu
    Text(
        text,
        Modifier
            .clip(RoundedCornerShape(5.dp))
            .background(if (accent) c.accentSoft else c.sunken)
            .padding(horizontal = 5.dp, vertical = 2.dp),
        fontSize = 9.5.sp,
        fontWeight = FontWeight.Bold,
        color = if (accent) c.accentText else c.textSub,
    )
}

@Composable
private fun Item(text: String, danger: Boolean = false, onClick: () -> Unit) {
    val c = pu
    DropdownMenuItem(
        text = { Text(text, fontSize = 14.sp, color = if (danger) c.danger else c.text) },
        onClick = onClick,
    )
}

/** The full-page editor: name, description and the prompt itself, with Save and Cancel. */
@Composable
private fun PromptEditor(
    draft: Draft,
    onChange: (Draft) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = pu
    Column(
        modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(if (draft.original == null) tr("New prompt", "새 프롬프트") else tr("Edit prompt", "프롬프트 편집"), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = c.textSub)
        PromptField(
            label = tr("Name", "이름"),
            value = draft.name,
            onChange = { onChange(draft.copy(name = it.take(80))) },
            placeholder = tr("e.g. Short answers in Bangla", "예: 방글라어로 짧게 답하기"),
            singleLine = true,
        )
        PromptField(
            label = tr("Description", "설명"),
            value = draft.description,
            onChange = { onChange(draft.copy(description = it.take(200))) },
            placeholder = tr("What this prompt is for", "이 프롬프트의 용도"),
            singleLine = true,
        )
        PromptField(
            label = tr("Prompt", "프롬프트 내용"),
            value = draft.content,
            onChange = { onChange(draft.copy(content = it)) },
            placeholder = tr("Write the instructions the AI should follow…", "AI가 따를 지침을 적어 주세요…"),
            singleLine = false,
            minLines = 10,
            maxLines = 16,
        )
        Text(
            tr("${draft.content.length} characters. Long, multiline prompts are kept exactly as written.", "${draft.content.length}자. 길고 여러 줄인 프롬프트도 그대로 저장돼요."),
            style = MonoStyle,
            fontSize = 11.5.sp,
            color = c.textSub,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PuButton(tr("Cancel", "취소"), onCancel, Modifier.weight(1f), kind = ButtonKind.OUTLINE, height = 46.dp, fontSize = 14)
            PuButton(tr("Save", "저장"), onSave, Modifier.weight(1f), height = 46.dp, fontSize = 14)
        }
    }
}

@Composable
private fun PromptField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    singleLine: Boolean,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
) {
    val c = pu
    Column {
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = c.textSub)
        Spacer(Modifier.height(6.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(c.sunken)
                .border(1.dp, c.inputBorder, RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            if (value.isEmpty()) {
                Text(placeholder, fontSize = 14.sp, lineHeight = 21.sp, color = c.textSub.copy(alpha = 0.7f))
            }
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = singleLine,
                minLines = minLines,
                maxLines = maxLines,
                textStyle = TextStyle(fontFamily = PlexSans, fontSize = 14.sp, lineHeight = 21.sp, color = c.text),
                cursorBrush = SolidColor(c.accent),
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = label },
            )
        }
    }
}

/** Every layer the model receives, in order. Read-only layers are labelled and cannot be edited here. */
@Composable
private fun EffectivePreview(
    sections: List<PromptSection>,
    onCopy: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val c = pu
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.surface,
        shape = RoundedCornerShape(22.dp),
        title = { Text(tr("Effective prompt", "실제 적용되는 프롬프트"), fontSize = 18.sp, fontWeight = FontWeight.Bold, color = c.text) },
        text = {
            Column(
                Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(
                    tr("This is exactly what the selected AI provider receives, in this order. No API keys or account secrets are included.", "선택한 AI 제공자에 실제로 전달되는 내용이에요. 순서대로 보여 주고, API 키나 계정 정보는 들어 있지 않아요."),
                    fontSize = 12.5.sp,
                    lineHeight = 18.sp,
                    color = c.textSub,
                )
                for (section in sections) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(c.sunken)
                            .padding(12.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                section.title,
                                Modifier.weight(1f),
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (section.editable) c.accentText else c.textSub,
                            )
                            Text(
                                if (section.editable) tr("EDITABLE", "수정 가능") else tr("LOCKED", "잠김"),
                                style = MonoStyle,
                                fontSize = 9.5.sp,
                                color = if (section.editable) c.accentText else c.textSub,
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(section.body, fontSize = 12.sp, lineHeight = 17.sp, color = c.text2)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onCopy(sections.joinToString("\n\n") { it.body }) }) {
                Text(tr("Copy all", "전체 복사"), color = c.text, fontSize = 15.sp)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(tr("Close", "닫기"), color = c.text, fontSize = 15.sp) }
        },
    )
}

@Composable
private fun ConfirmDialog(
    title: String,
    body: String,
    confirm: String,
    danger: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val c = pu
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.surface,
        shape = RoundedCornerShape(22.dp),
        title = { Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = c.text) },
        text = { Text(body, fontSize = 14.sp, lineHeight = 20.sp, color = c.textSub) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirm, color = if (danger) c.danger else c.text, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(tr("Cancel", "취소"), color = c.text, fontSize = 15.sp) }
        },
    )
}

@Composable
private fun NewProfileDialog(onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    val c = pu
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.surface,
        shape = RoundedCornerShape(22.dp),
        title = { Text(tr("New profile", "새 프로필"), fontSize = 18.sp, fontWeight = FontWeight.Bold, color = c.text) },
        text = {
            Column {
                Text(
                    tr("A profile groups prompts. Each profile keeps its own active prompt, and switching profiles never deletes anything.", "프로필은 프롬프트를 묶어요. 프로필마다 활성 프롬프트가 따로 있고, 프로필을 바꿔도 아무것도 지워지지 않아요."),
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                    color = c.textSub,
                )
                Spacer(Modifier.height(12.dp))
                PromptField(
                    label = tr("Name", "이름"),
                    value = name,
                    onChange = { name = it.take(60) },
                    placeholder = tr("e.g. Work assistant", "예: 업무 도우미"),
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { if (name.isNotBlank()) onCreate(name) }) {
                Text(tr("Create", "만들기"), color = c.text, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(tr("Cancel", "취소"), color = c.text, fontSize = 15.sp) }
        },
    )
}

/** The editor's working copy. [dirty] compares it with what the store holds. */
private data class Draft(
    val original: SystemPrompt?,
    val profileId: String,
    val name: String,
    val description: String,
    val content: String,
    val createdAt: Long,
) {
    val dirty: Boolean
        get() = when (original) {
            null -> name.isNotBlank() || description.isNotBlank() || content.isNotBlank()
            else -> name != original.name || description != original.description || content != original.content
        }
}

private fun shortDate(millis: Long): String {
    if (millis <= 0L) return "—"
    return java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(java.util.Date(millis))
}

private fun copyText(context: android.content.Context, text: String) {
    val clipboard = context.getSystemService(android.content.ClipboardManager::class.java) ?: return
    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("OpenPhoneUse prompt", text))
}

private fun safeFileName(name: String): String {
    val clean = name.replace(Regex("[^A-Za-z0-9 _.-]"), "_").trim().take(60).ifEmpty { "prompt" }
    return if (clean.endsWith(".txt", ignoreCase = true)) clean else "$clean.txt"
}

private fun fileName(context: android.content.Context, uri: Uri): String {
    val fallback = uri.lastPathSegment ?: "import.txt"
    return try {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) ?: fallback else fallback
        } ?: fallback
    } catch (e: Exception) {
        fallback
    }
}
