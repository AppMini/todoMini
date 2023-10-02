![Screenshot](./screenshot.png)

Minimal TODO list web app:

 * Web based.
 * Easy to deploy.
 * Self-hosted & FLOSS.
 * Allows multiple people to update a list.
 * Mobile friendly - "Add to Home Screen" webapp.
 * Simple text based format for easy editing (see below).

Surprisingly, I could not find software meeting these criteria.

### Self-hosted Install

 * Download [a release](https://github.com/chr15m/omgnata/releases) or `git clone` the `gh-pages` branch.
 * Copy the files to your PHP web hosting.

Note: ensure that the `data/` directory and files therein are writable by the user/group running PHP.

[If you use todoMini, please give it a 'like' on alternativeto.net](https://alternativeto.net/software/todomini/)

### Authentication

To require authentication, first create a password file:

	htpasswd -c /path/to/.htpasswd username

Then copy `./example.htaccess` to `.htaccess` and edit it.

### Multi-user

If you have multiple users, clone the directory for each user and create a unique htaccess login for each.

Alternatively, you can create the environment variable TODO_PER_USER_FOLDERS with the value set to "TRUE".
Then just add each user to the same htpassword file.  This will create per user fodlers within the data folder, and use the correct one based on currently logged in user.  New folders will be created automatically.

To create a shared list, symlink one of the TODO list files into each user's data directory.

### Textfile format

The TODO lists use standard Markdown format:

	 * [x] This is my first completed item.
	 * [ ] Another uncompleted item.
	 * [ ] Something else.

### License

Copyright Chris McCormick, 2016. [GPLv3 licensed](./LICENSE.txt).
