<?php
/**
 * Infinite looping PHP long-poller.
 * NOTE: this is unauthenticated so put it behind e.g. htaccess and HTTPS
 */
// original from here:
// https://github.com/panique/php-long-polling/blob/master/server/server.php

// enable cors
cors();

// Set data directory to data if no one logged in
// Otherwise to data/<username>
$dir = "data";
if (isset($_ENV["TODO_PER_USER_FOLDERS"]) && $_ENV["TODO_PER_USER_FOLDERS"] == 'TRUE') {

  $dir = "data/".$_SERVER['PHP_AUTH_USER'];
  if (!file_exists($dir)) {
    mkdir($dir, 0777);
  }
}

if ($_SERVER['REQUEST_METHOD'] == 'POST') {
  if (isset($_POST["filename"])) {
    updateFile($_POST, $dir);
  } elseif (isset($_POST["delete"])) {
    deleteFile($_POST, $dir);
  }
} else {
  dirPoller($dir);
}

function updateFile($update, $dir) {
  $filename = basename($update["filename"]);
  if (endsWith($filename, ".txt")) {
    file_put_contents($dir . "/" . $filename, isset($update["content"]) ? $update["content"] : "");
    echo json_encode(microtime(True));
  } else {
    echo json_encode(null);
  }
}

function deleteFile($delete, $dir) {
  $filename = $dir . "/" . basename($delete["delete"]);
  if (endsWith($filename, ".txt") && file_exists($filename)) {
    unlink($filename);
    echo json_encode(microtime(True));
  } else {
    echo json_encode(null);
  }
}

function dirPoller($datadir) {
  // hang for a maximum of 30 seconds
  $live_for = isset($_GET['live_for']) ? min((int)$_GET['live_for'], 180) : 30;
  // tell PHP it can timeout after 30 seconds
  set_time_limit($live_for);
  ini_set("max_execution_time", $live_for);
  // whether the loop has returned anything or not
  $has_returned = false;
  // start the wait-poll loop
  while ($live_for--) {
    // if ajax request has send a timestamp, then $last_ajax_call = timestamp, else $last_ajax_call = null
    $last_ajax_call = isset($_GET['timestamp']) ? (float)$_GET['timestamp'] : null;
    // get timestamp of when file has been changed the last time
    $last_change_in_data_files = dirTimestamp($datadir);
    // if no timestamp delivered via ajax or data.txt has been changed SINCE last ajax timestamp
    if ($last_ajax_call == null || (int)($last_change_in_data_files * 1000) > (int)($last_ajax_call * 1000)) {
        // get content of data.txt
        $data = dirFiles($datadir);
        // put data.txt's content and timestamp of last data.txt change into array
        $data["timestamp"] = $last_change_in_data_files;
        // encode to JSON, render the result (for AJAX)
        echo json_encode($data);
        // leave this loop step
        $has_returned = true;
        break;
    } else {
        // block for 1 second
        sleep(1);
        continue;
    }
  }
  if (!$has_returned) {
    echo json_encode(array("timestamp" => $last_change_in_data_files));
  }
}

// Open a directory, and read its contents
// http://www.w3schools.com/php/func_directory_readdir.asp
function dirFiles($datadir) {
  $files = array();
  $creation_timestamps = array();
  if ($dh = opendir($datadir)) {
    while (($filename = readdir($dh)) !== false){
      if (endsWith($filename, ".txt")) {
        $filepath = $datadir . "/" . $filename;
        $files[$filename] = file_get_contents($filepath);
        $creation_timestamps[$filename] = filectime($filepath);
      }
    }
    closedir($dh);
  }
  return Array("files" => $files, "creation_timestamps" => $creation_timestamps);
}

function dirTimestamp($datadir) {
  // PHP caches file data, like requesting the size of a file, by default. clearstatcache() clears that cache
  clearstatcache();
  $timestamp = filemtime_precise($datadir . "/.");
  if ($dh = opendir($datadir)){
    while (($filename = readdir($dh)) !== false){
      if (endsWith($filename, ".txt")) {
        $filepath = $datadir . "/" . $filename;
        $file_timestamp = filemtime_precise($filepath);
        if ($file_timestamp > $timestamp) {
          $timestamp = $file_timestamp;
        }
      }
    }
    closedir($dh);
  }
  return $timestamp;
}


// test if a string ends with another string
// http://stackoverflow.com/a/619725/2131094
function endsWith($string, $test) {
  $strlen = strlen($string);
  $testlen = strlen($test);
  if ($testlen > $strlen) return false;
  return substr_compare($string, $test, $strlen - $testlen, $testlen) === 0;
}


// get precise file modification time (including milliseconds)
// http://stackoverflow.com/a/20248406/2131094
function filemtime_precise($path){
    $dateUnix = shell_exec('stat --format "%y" \'' . $path . '\'');
    $date = explode(".", $dateUnix);
    return (float)(filemtime($path).".".substr(isset($date[1]) ? $date[1] : null, 0, 8));
}

function cors() {
    // Allow from any origin
    if (isset($_SERVER['HTTP_ORIGIN'])) {
        header("Access-Control-Allow-Origin: {$_SERVER['HTTP_ORIGIN']}");
        header('Access-Control-Allow-Credentials: true');
        header('Access-Control-Max-Age: 86400');    // cache for 1 day
    }
    // Access-Control headers are received during OPTIONS requests
    if ($_SERVER['REQUEST_METHOD'] == 'OPTIONS') {
        if (isset($_SERVER['HTTP_ACCESS_CONTROL_REQUEST_METHOD']))
            header("Access-Control-Allow-Methods: GET, POST, PUT, DELETE, OPTIONS");
        if (isset($_SERVER['HTTP_ACCESS_CONTROL_REQUEST_HEADERS']))
            header("Access-Control-Allow-Headers: {$_SERVER['HTTP_ACCESS_CONTROL_REQUEST_HEADERS']}");
        exit(0);
    }
}
?>
