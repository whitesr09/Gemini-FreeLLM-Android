package com.nshd.geminifreellm.ui
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
private fun iconFor(key:String):ImageVector=when(key){
 "menu"->Icons.Filled.Menu;"model"->Icons.Filled.ExpandMore;"add"->Icons.Filled.Add;"settings"->Icons.Filled.Settings
 "attach"->Icons.Filled.AttachFile;"mic"->Icons.Filled.Mic;"web"->Icons.Filled.Public;"tools"->Icons.Filled.Build
 "image"->Icons.Filled.Image;"video"->Icons.Filled.Videocam;"send"->Icons.Filled.Send;"stop"->Icons.Filled.Stop
 "copy"->Icons.Filled.ContentCopy;"share"->Icons.Filled.Share;"read"->Icons.Filled.VolumeUp;"edit"->Icons.Filled.Edit
 "retry"->Icons.Filled.Refresh;"save"->Icons.Filled.Save;"download"->Icons.Filled.FileDownload;"delete"->Icons.Filled.Delete
 "star"->Icons.Filled.Star;"starBorder"->Icons.Filled.StarBorder;"archive"->Icons.Filled.Archive;"unarchive"->Icons.Filled.Unarchive
 "search"->Icons.Filled.Search;"check"->Icons.Filled.Check;"close"->Icons.Filled.Close;"more"->Icons.Filled.MoreVert
 "language"->Icons.Filled.Language;else->Icons.Filled.MoreVert
}
@Composable fun AppIcon(key:String,contentDescription:String?,modifier:Modifier=Modifier,tint:Color=LocalAppColors.current.muted)=Icon(iconFor(key),contentDescription,modifier,tint)
@Composable fun AppIconButton(key:String,contentDescription:String,onClick:()->Unit,tint:Color=LocalAppColors.current.muted){
 Box(Modifier.size(48.dp).clickable(onClick=onClick),contentAlignment=Alignment.Center){AppIcon(key,contentDescription,Modifier.size(24.dp),tint)}
}
