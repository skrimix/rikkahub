package me.rerere.rikkahub.ui.pages.chat

import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.collectAsLazyPagingItems
import com.dokar.sonner.ToastType
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Folder01
import me.rerere.hugeicons.stroke.FolderAdd
import me.rerere.hugeicons.stroke.Image02
import me.rerere.hugeicons.stroke.InLove
import me.rerere.hugeicons.stroke.LanguageCircle
import me.rerere.hugeicons.stroke.PencilEdit01
import me.rerere.hugeicons.stroke.Search01
import me.rerere.hugeicons.stroke.Settings03
import me.rerere.hugeicons.stroke.Sparkles
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.Folder
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.ui.components.ai.AssistantPicker
import me.rerere.rikkahub.ui.components.ui.BackupReminderCard
import me.rerere.rikkahub.ui.components.ui.Greeting
import me.rerere.rikkahub.ui.components.ui.Tooltip
import me.rerere.rikkahub.ui.components.ui.UIAvatar
import me.rerere.rikkahub.ui.components.ui.UpdateCard
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.context.Navigator
import me.rerere.rikkahub.ui.hooks.EditStateContent
import me.rerere.rikkahub.ui.hooks.readBooleanPreference
import me.rerere.rikkahub.ui.hooks.rememberIsPlayStoreVersion
import me.rerere.rikkahub.ui.hooks.useEditState
import me.rerere.rikkahub.utils.navigateToChatPage
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import kotlin.uuid.Uuid

@Composable
fun ChatDrawerContent(
    navController: Navigator,
    vm: ChatVM,
    settings: Settings,
    current: Conversation,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val isPlayStore = rememberIsPlayStoreVersion()
    val repo = koinInject<ConversationRepository>()
    val userNickname = settings.displaySetting.userNickname.ifBlank { stringResource(R.string.user_default_name) }

    val activity = context as ComponentActivity
    val drawerVm: ChatDrawerVM = koinViewModel(viewModelStoreOwner = activity)

    val conversations = drawerVm.conversations.collectAsLazyPagingItems()
    val folders by drawerVm.folders.collectAsStateWithLifecycle()
    val selectedFolderId by drawerVm.selectedFolderId.collectAsStateWithLifecycle()
    val conversationListState = rememberLazyListState(
        initialFirstVisibleItemIndex = drawerVm.scrollIndex,
        initialFirstVisibleItemScrollOffset = drawerVm.scrollOffset,
    )

    LaunchedEffect(conversationListState) {
        snapshotFlow {
            conversationListState.firstVisibleItemIndex to
                conversationListState.firstVisibleItemScrollOffset
        }
            .distinctUntilChanged()
            .collectLatest { (index, offset) ->
                drawerVm.saveScrollPosition(index, offset)
            }
    }

    val conversationJobs by vm.conversationJobs.collectAsStateWithLifecycle(
        initialValue = emptyMap(),
    )

    // 昵称编辑状态
    val nicknameEditState = useEditState<String> { newNickname ->
        vm.updateSettings(
            settings.copy(
                displaySetting = settings.displaySetting.copy(
                    userNickname = newNickname
                )
            )
        )
    }

    // 移动对话状态
    var showMoveToAssistantSheet by remember { mutableStateOf(false) }
    var conversationToMove by remember { mutableStateOf<Conversation?>(null) }
    val bottomSheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden)

    // 文件夹相关状态
    var showMoveToFolderSheet by remember { mutableStateOf(false) }
    var conversationToMoveFolder by remember { mutableStateOf<Conversation?>(null) }
    val folderSheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden)
    var showCreateFolderDialog by remember { mutableStateOf(false) }
    var folderToRename by remember { mutableStateOf<Folder?>(null) }
    var folderToDelete by remember { mutableStateOf<Folder?>(null) }

    // Menu popup 状态
    var showMenuPopup by remember { mutableStateOf(false) }

    val updateCheckDisabledUntil = settings.displaySetting.updateCheckDisabledUntilEpochMillis
    var updateChecksEnabled by remember(updateCheckDisabledUntil) {
        mutableStateOf(updateCheckDisabledUntil <= System.currentTimeMillis())
    }
    LaunchedEffect(updateCheckDisabledUntil) {
        while (true) {
            val remaining = updateCheckDisabledUntil - System.currentTimeMillis()
            if (remaining <= 0) {
                updateChecksEnabled = true
                break
            }
            updateChecksEnabled = false
            delay(minOf(remaining, 60 * 60 * 1_000L))
        }
    }

    ModalDrawerSheet(
        modifier = Modifier.width(300.dp)
    ) {
        Column(
            modifier = Modifier.padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (updateChecksEnabled && !isPlayStore) {
                UpdateCard(vm)
            }

            BackupReminderCard(
                settings = settings,
                onClick = { navController.navigate(Screen.Backup) },
            )

            // 用户头像和昵称自定义区域
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                UIAvatar(
                    name = userNickname,
                    value = settings.displaySetting.userAvatar,
                    showEditIcon = false,
                    onUpdate = { newAvatar ->
                        vm.updateSettings(
                            settings.copy(
                                displaySetting = settings.displaySetting.copy(
                                    userAvatar = newAvatar
                                )
                            )
                        )
                    },
                    modifier = Modifier.size(50.dp),
                )

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .clip(MaterialTheme.shapes.small)
                        .clickable(onClickLabel = stringResource(R.string.chat_page_edit_nickname)) {
                            nicknameEditState.open(settings.displaySetting.userNickname)
                        },
                    verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
                ) {
                    Text(
                        text = userNickname,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Greeting(
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }

            AssistantPicker(
                settings = settings,
                onUpdateSettings = {
                    val updateJob = vm.updateSettings(it)
                    scope.launch {
                        updateJob.join()
                        val id = if (context.readBooleanPreference("create_new_conversation_on_start", true)) {
                            Uuid.random()
                        } else {
                            repo.getConversationsOfAssistant(it.assistantId)
                                .first()
                                .firstOrNull()
                                ?.id ?: Uuid.random()
                        }
                        navigateToChatPage(navigator = navController, chatId = id)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )

            FolderBar(
                folders = folders,
                selectedFolderId = selectedFolderId,
                onSelect = { drawerVm.selectFolder(it) },
                onCreate = { showCreateFolderDialog = true },
                onRename = { folderToRename = it },
                onDelete = { folderToDelete = it },
                onSearch = { navController.navigate(Screen.MessageSearch) },
            )

            ConversationList(
                current = current,
                conversations = conversations,
                conversationJobs = conversationJobs.keys,
                listState = conversationListState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                onClick = {
                    navigateToChatPage(navController, it.id)
                },
                onRegenerateTitle = {
                    vm.generateTitle(it, true)
                },
                onDelete = {
                    scope.launch {
                        vm.deleteConversation(it).join()
                        conversations.refresh()
                        if (it.id == current.id) {
                            navigateToChatPage(navController)
                        }
                    }
                },
                onPin = {
                    vm.updatePinnedStatus(it)
                },
                onMoveToAssistant = {
                    conversationToMove = it
                    showMoveToAssistantSheet = true
                },
                onMoveToFolder = {
                    conversationToMoveFolder = it
                    showMoveToFolderSheet = true
                }
            )

            HorizontalDivider(modifier = Modifier.padding(horizontal = 8.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp)
            ) {
                DrawerAction(
                    icon = HugeIcons.InLove,
                    label = stringResource(R.string.favorite_page_title),
                    onClick = { navController.navigate(Screen.Favorite) },
                    modifier = Modifier.weight(1f),
                )

                Box(modifier = Modifier.weight(1f)) {
                    DrawerAction(
                        icon = HugeIcons.Sparkles,
                        label = "Tools",
                        onClick = { showMenuPopup = true },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    DropdownMenu(
                        expanded = showMenuPopup,
                        onDismissRequest = { showMenuPopup = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.chat_page_menu_ai_translator)) },
                            leadingIcon = { Icon(HugeIcons.LanguageCircle, null) },
                            onClick = {
                                showMenuPopup = false
                                navController.navigate(Screen.Translator)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.chat_page_menu_image_generation)) },
                            leadingIcon = { Icon(HugeIcons.Image02, null) },
                            onClick = {
                                showMenuPopup = false
                                navController.navigate(Screen.ImageGen)
                            }
                        )
                    }
                }

                DrawerAction(
                    icon = HugeIcons.Settings03,
                    label = stringResource(R.string.settings),
                    onClick = { navController.navigate(Screen.Setting) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }

    // 昵称编辑对话框
    nicknameEditState.EditStateContent { nickname, onUpdate ->
        AlertDialog(
            onDismissRequest = {
                nicknameEditState.dismiss()
            },
            title = {
                Text(stringResource(R.string.chat_page_edit_nickname))
            },
            text = {
                OutlinedTextField(
                    value = nickname,
                    onValueChange = onUpdate,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.chat_page_nickname_placeholder)) }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        nicknameEditState.confirm()
                    }
                ) {
                    Text(stringResource(R.string.chat_page_save))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        nicknameEditState.dismiss()
                    }
                ) {
                    Text(stringResource(R.string.chat_page_cancel))
                }
            }
        )
    }

    // 移动到文件夹 Bottom Sheet
    if (showMoveToFolderSheet) {
        val doMove: (Uuid?) -> Unit = { folderId ->
            conversationToMoveFolder?.let { conversation ->
                drawerVm.moveConversationToFolder(conversation.id, folderId)
                scope.launch {
                    folderSheetState.hide()
                    showMoveToFolderSheet = false
                    conversationToMoveFolder = null
                    conversations.refresh()
                }
            }
        }
        ModalBottomSheet(
            onDismissRequest = {
                showMoveToFolderSheet = false
                conversationToMoveFolder = null
            },
            sheetState = folderSheetState
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = stringResource(R.string.chat_page_move_to_folder),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                // 移出文件夹（未归类）
                Surface(
                    onClick = { doMove(null) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    color = if (conversationToMoveFolder?.folderId == null) {
                        MaterialTheme.colorScheme.surfaceVariant
                    } else {
                        MaterialTheme.colorScheme.surface
                    },
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(HugeIcons.Folder01, null)
                        Text(
                            text = stringResource(R.string.chat_page_remove_from_folder),
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                }

                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(folders) { folder ->
                        val isCurrent = folder.id == conversationToMoveFolder?.folderId
                        Surface(
                            onClick = { doMove(folder.id) },
                            modifier = Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.medium,
                            color = if (isCurrent) {
                                MaterialTheme.colorScheme.surfaceVariant
                            } else {
                                MaterialTheme.colorScheme.surface
                            },
                            tonalElevation = if (isCurrent) 2.dp else 0.dp
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Icon(HugeIcons.Folder01, null)
                                Text(
                                    text = folder.name,
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // 新建文件夹对话框
    if (showCreateFolderDialog) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showCreateFolderDialog = false },
            title = { Text(stringResource(R.string.chat_page_create_folder)) },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.chat_page_folder_name)) }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        drawerVm.createFolder(name)
                        showCreateFolderDialog = false
                    },
                    enabled = name.isNotBlank()
                ) { Text(stringResource(R.string.chat_page_save)) }
            },
            dismissButton = {
                TextButton(onClick = { showCreateFolderDialog = false }) {
                    Text(stringResource(R.string.chat_page_cancel))
                }
            }
        )
    }

    // 重命名文件夹对话框
    folderToRename?.let { folder ->
        var name by remember(folder.id) { mutableStateOf(folder.name) }
        AlertDialog(
            onDismissRequest = { folderToRename = null },
            title = { Text(stringResource(R.string.chat_page_rename_folder)) },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        drawerVm.renameFolder(folder.id, name)
                        folderToRename = null
                    },
                    enabled = name.isNotBlank()
                ) { Text(stringResource(R.string.chat_page_save)) }
            },
            dismissButton = {
                TextButton(onClick = { folderToRename = null }) {
                    Text(stringResource(R.string.chat_page_cancel))
                }
            }
        )
    }

    // 删除文件夹确认
    folderToDelete?.let { folder ->
        AlertDialog(
            onDismissRequest = { folderToDelete = null },
            title = { Text(stringResource(R.string.chat_page_delete_folder)) },
            text = { Text(stringResource(R.string.chat_page_delete_folder_confirm, folder.name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (drawerVm.deleteFolder(folder.id)) {
                            folderToDelete = null
                            conversations.refresh()
                        } else {
                            toaster.show(context.getString(R.string.chat_page_delete_folder_generating), type = ToastType.Warning)
                        }
                    }
                ) { Text(stringResource(R.string.chat_page_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { folderToDelete = null }) {
                    Text(stringResource(R.string.chat_page_cancel))
                }
            }
        )
    }

    // 移动到助手 Bottom Sheet
    if (showMoveToAssistantSheet) {
        ModalBottomSheet(
            onDismissRequest = {
                showMoveToAssistantSheet = false
                conversationToMove = null
            },
            sheetState = bottomSheetState
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = stringResource(R.string.chat_page_move_to_assistant),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(settings.assistants) { assistant ->
                        AssistantItem(
                            assistant = assistant,
                            isCurrentAssistant = assistant.id == conversationToMove?.assistantId,
                            onClick = {
                                conversationToMove?.let { conversation ->
                                    vm.moveConversationToAssistant(conversation, assistant.id)
                                    scope.launch {
                                        bottomSheetState.hide()
                                        showMoveToAssistantSheet = false
                                        conversationToMove = null
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DrawerAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier) {
        Tooltip(
            tooltip = { Text(label) },
        ) {
            Surface(
                onClick = onClick,
                modifier = Modifier.fillMaxWidth(),
                color = Color.Transparent,
                shape = MaterialTheme.shapes.medium,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(22.dp))
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun FolderBar(
    folders: List<Folder>,
    selectedFolderId: Uuid?,
    onSelect: (Uuid?) -> Unit,
    onCreate: () -> Unit,
    onRename: (Folder) -> Unit,
    onDelete: (Folder) -> Unit,
    onSearch: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LazyRow(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            item {
                FolderChip(
                    label = stringResource(R.string.chat_page_folder_default),
                    selected = selectedFolderId == null,
                    onClick = { onSelect(null) },
                    onLongClick = {},
                )
            }
            items(folders, key = { it.id.toString() }) { folder ->
                var menuExpanded by remember { mutableStateOf(false) }
                Box {
                    FolderChip(
                        label = folder.name,
                        icon = HugeIcons.Folder01,
                        selected = selectedFolderId == folder.id,
                        onClick = { onSelect(folder.id) },
                        onLongClick = { menuExpanded = true },
                    )
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.chat_page_rename)) },
                            leadingIcon = { Icon(HugeIcons.PencilEdit01, null) },
                            onClick = {
                                onRename(folder)
                                menuExpanded = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.chat_page_delete)) },
                            leadingIcon = { Icon(HugeIcons.Delete01, null) },
                            onClick = {
                                onDelete(folder)
                                menuExpanded = false
                            }
                        )
                    }
                }
            }
            item {
                FolderChip(
                    label = stringResource(R.string.chat_page_folder_add),
                    icon = HugeIcons.FolderAdd,
                    selected = false,
                    onClick = onCreate,
                    onLongClick = {},
                )
            }
        }
        Tooltip(
            tooltip = { Text(stringResource(R.string.chat_page_search_chats)) },
        ) {
            IconButton(onClick = onSearch) {
                Icon(
                    imageVector = HugeIcons.Search01,
                    contentDescription = stringResource(R.string.chat_page_search_chats),
                )
            }
        }
    }
}

@Composable
private fun FolderChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    icon: ImageVector? = null,
) {
    Surface(
        shape = CircleShape,
        color = if (selected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        },
        modifier = Modifier
            .clip(CircleShape)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (icon != null) {
                Icon(icon, null, modifier = Modifier.size(14.dp))
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun AssistantItem(
    assistant: Assistant,
    isCurrentAssistant: Boolean,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = if (isCurrentAssistant) {
            MaterialTheme.colorScheme.surfaceVariant
        } else {
            MaterialTheme.colorScheme.surface
        },
        tonalElevation = if (isCurrentAssistant) 2.dp else 0.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            UIAvatar(
                name = assistant.name,
                value = assistant.avatar,
                onUpdate = {},
                modifier = Modifier.size(40.dp),
            )
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = assistant.name.ifBlank { stringResource(R.string.assistant_page_default_assistant) },
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (isCurrentAssistant) {
                    Text(
                        text = stringResource(R.string.assistant_page_current_assistant),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
