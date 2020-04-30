#include <sys/types.h>
#include <sys/sendfile.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <stdlib.h>
#include <stdio.h>
#include <pthread.h>
#include <unistd.h>
#include <limits.h>
#include <string.h>
#include <stdbool.h>
#include <arpa/inet.h>
#include <ifaddrs.h>
#include <netinet/in.h>
#include <fcntl.h>
#include "dir.h"
#include "usage.h"

#define BACKLOGSIZE 1
#define SUCCESS 1
#define FAILURE -1

int pasvSocketFd = 0;

int parseClientInput(int clientId, char buf[1024], char * cmd, char * argument);
int sendMessage(int clientId, char * message);
int pasvCommand(int clientId);
void modeCommand(int clientId, char * argument);
void typeCommand(int clientId, char * argument);
void struCommand(int clientId, char * argument);
void nlstCommand(int clientId);
void cwdCommand(int clientId, char * argument);
void cdupCommand(int clientId, char * initialWorkingDirectory);
void retrCommand(int clientId, char * argument);

// parses user input and calls proper command or reacts accordingly to errors
void* interact(void* args) {
    int clientId = *(int*) args;
    
    // Interact with the client
    char buffer[1024];
    char * cmd = malloc(sizeof(char) * 1024);
    char * argument = malloc(sizeof(char) * 1024);
    bool isLoggedIn = false;

    char initialWorkingDirectory[4096];
    getcwd(initialWorkingDirectory, sizeof(initialWorkingDirectory));

    while (true) {
        bzero(buffer, 1024);
        bzero(cmd, 1024);
        bzero(argument, 1024);

        // Receive the client message
        ssize_t length = recv(clientId, buffer, 1024, 0);
        if (length < 0) {
            perror("Failed to read from the socket");
            break;
        }
        
        if (length == 0) {
            printf("EOF\n");
            break;
        }

        int numInputs = parseClientInput(clientId, buffer, cmd, argument);

        if (strcasecmp(cmd, "QUIT") == 0) {
          if (numInputs != 1) {
            snprintf(buffer, 1024, "%s\r\n", "501 Syntax error in parameters or arguments.");
            sendMessage(clientId, buffer);
          } else {
            snprintf(buffer, 1024, "%s\r\n", "221 Goodbye");
            sendMessage(clientId, buffer);
            break;
          }

        } else if (strcasecmp(cmd, "USER") == 0) {
          if (numInputs != 2) {
            snprintf(buffer, 1024, "%s\r\n", "501 Syntax error in parameters or arguments.");
            sendMessage(clientId, buffer);
          } else if (strcmp(argument, "cs317") == 0 && !isLoggedIn) {
            snprintf(buffer, 1024, "%s\r\n", "230 User logged in, proceed.");
            sendMessage(clientId, buffer);
            isLoggedIn = true;
          } else {
            if (isLoggedIn) {
              snprintf(buffer, 1024, "%s\r\n", "530 Already logged in.");
              sendMessage(clientId, buffer);
            } else {
              length = snprintf(buffer, 1024, "%s\r\n", "530 Not logged in.");
              sendMessage(clientId, buffer);
            }
          }
        } else if (strcasecmp(cmd, "PASS") == 0) {
          snprintf(buffer, 1024, "%s\r\n", "221 no password needed");
          sendMessage(clientId, buffer);

        } else if (!isLoggedIn) {
          snprintf(buffer, 1024, "%s\r\n", "530 Not logged in.");
          sendMessage(clientId, buffer);

        } else if (strcasecmp(cmd, "CWD") == 0) {
          if (numInputs != 2) {
            snprintf(buffer, 1024, "%s\r\n", "501 Syntax error in parameters or arguments.");
            sendMessage(clientId, buffer);
          } else {
            cwdCommand(clientId, argument);
          }
          
        } else if (strcasecmp(cmd, "CDUP") == 0) {
          if (numInputs != 1) {
            snprintf(buffer, 1024, "%s\r\n", "501 Syntax error in parameters or arguments.");
            sendMessage(clientId, buffer);
          } else {
            cdupCommand(clientId, initialWorkingDirectory);
          }
          
        } else if (strcasecmp(cmd, "TYPE") == 0) {
          if (numInputs != 2) {
            snprintf(buffer, 1024, "%s\r\n", "501 Syntax error in parameters or arguments.");
            sendMessage(clientId, buffer);
          } else {
            typeCommand(clientId, argument);
          }
          
        } else if (strcasecmp(cmd, "MODE") == 0) {
          if (numInputs != 2) {
            snprintf(buffer, 1024, "%s\r\n", "501 Syntax error in parameters or arguments.");
            sendMessage(clientId, buffer);
          } else {
            modeCommand(clientId, argument);
          }
          
        } else if (strcasecmp(cmd, "STRU") == 0) {
          if (numInputs != 2) {
            snprintf(buffer, 1024, "%s\r\n", "501 Syntax error in parameters or arguments.");
            sendMessage(clientId, buffer);
          } else {
            struCommand(clientId, argument);
          }
          
        } else if (strcasecmp(cmd, "RETR") == 0) {
          if (numInputs != 2) {
            snprintf(buffer, 1024, "%s\r\n", "501 Syntax error in parameters or arguments.");
            sendMessage(clientId, buffer);
          } else {
            retrCommand(clientId, argument);
          }

        } else if (strcasecmp(cmd, "PASV") == 0) {
          if (numInputs != 1) {
            snprintf(buffer, 1024, "%s\r\n", "501 Syntax error in parameters or arguments.");
            sendMessage(clientId, buffer);
          } else {
            pasvCommand(clientId);
          }
          
        } else if (strcasecmp(cmd, "NLST") == 0) {
          if (numInputs != 1) {
            snprintf(buffer, 1024, "%s\r\n", "501 NLST with parameters is not supported");
            sendMessage(clientId, buffer);
          } else {
            nlstCommand(clientId);
          }
        } else {
          snprintf(buffer, 1024, "%s\r\n", "500 Syntax error, command unrecognized.");
          sendMessage(clientId, buffer);
        }
    }
    free(cmd);
    free(argument);
    close(clientId);
    
    return NULL;
}

// starts the server
int main(int argc, char *argv[]) {
    // Check the command line arguments
    if (argc != 2) {
      usage(argv[0]);
      return -1;
    }
    
    uint64_t socketPort = atoi(argv[1]);
    if (socketPort < 1024 || socketPort > 65535) {
      usage(argv[0]);
      return -1;
    }

    // Create a TCP Socket
    int socketId = socket(PF_INET, SOCK_STREAM, 0);
    if (socketId < 0) {
      perror("Failed to create the socket.");
      exit(EXIT_FAILURE);
    }

    int value = 1; 
    if (setsockopt(socketId, SOL_SOCKET, SO_REUSEADDR, &value, sizeof(int)) != 0) {
      perror("Failed to set the socket options");
      exit(EXIT_FAILURE);
    }

    // Bind the newly created TCP socket
    struct sockaddr_in address; 
    bzero(&address, sizeof(struct sockaddr_in));
    address.sin_family = AF_INET;
    address.sin_port = htons(socketPort);
    address.sin_addr.s_addr = INADDR_ANY;

    if (bind(socketId, (const struct sockaddr*) &address, sizeof(struct sockaddr_in)) != 0) {
      perror("Failed to bind socket");
      exit(EXIT_FAILURE);
    }

    // Set up the socket to listen
    if (listen(socketId, BACKLOGSIZE) != 0) {
      perror("Failed to listen for connections");
      exit(EXIT_FAILURE);
    }
    
    while (true) {
      // Accept the connection
      struct sockaddr_in clientAddress;
      socklen_t clientAddressLength = sizeof(struct sockaddr_in);

      printf("Waiting for incoming connections...\n");

      int clientId = accept(socketId, (struct sockaddr*) &clientAddress, &clientAddressLength);
      if (clientId < 0) {
        perror("Failed to accept the client connection");
        continue;
      }

      printf("Accepted the client connection from %s:%d.\n", inet_ntoa(clientAddress.sin_addr), ntohs(clientAddress.sin_port));

      // Handle Login Sequence before making new thread
      char * loginMessage = "220 Service ready for new user.\r\n";
      if (sendMessage(clientId, loginMessage) == FAILURE) {
        continue;
      }

      // Create a separate thread to interact with the client
      pthread_t thread;
      if (pthread_create(&thread, NULL, interact, &clientId) != 0) {
          perror("Failed to create the thread");
          continue;
      }
        
      // The main thread just waits until the interaction is done
      pthread_join(thread, NULL);
      printf("Interaction thread has finished.\n");
    }
    return 0;
}

// Parse the client input, returns number of inputs (3, if 3 or more inputs)
int parseClientInput(int clientId, char buf[1024], char * cmd, char * argument) {
  char * temp; // temp variable used to check if there is more than 1 argument
  return sscanf(buf, "%s %s %s", cmd, argument, temp);
}

// sends a message to the given clientId
// return FAILURE if any failures and SUCCESS if no failures
int sendMessage(int clientId, char * message) {
  if (send(clientId, message, strlen(message), 0) != strlen(message)) {
    perror("Failed to send to the socket");
    return FAILURE;
  }
  return SUCCESS;
}

// sends message with the pasv connection IP address and encoded port number
int sendPasvMessageToClient(int clientId, int pasvSocketFd) {
  struct sockaddr_in pasvAddr;
  socklen_t pasvAddrLen = sizeof(struct sockaddr_in);

  if (getsockname(pasvSocketFd, (struct sockaddr*) &pasvAddr, &pasvAddrLen) != 0) {
    perror("Failed to get pasv Socket name");
    return FAILURE;
  }

  int portNum = ntohs(pasvAddr.sin_port);
  int portNum0 = (portNum >> 8) & 0xff;
  int portNum1 = portNum & 0xff;

  char buffer[1024];
  bzero(&buffer, sizeof(buffer));
  int address = pasvAddr.sin_addr.s_addr;
  snprintf(buffer, 1024, "227 Entering Passive Mode (%d,%d,%d,%d,%d,%d).\r\n", address & 0xff,
                                                                          (address >> 8) & 0xff,
                                                                          (address >> 16) & 0xff,
                                                                          (address >> 24) & 0xff,
                                                                          portNum0,
                                                                          portNum1);
  sendMessage(clientId, buffer);
  return SUCCESS;
}

// creates binds and listens on a pasv socket
// also sets the IP address and port number to the pasv socket
int pasvCommand(int clientId) {
  if (pasvSocketFd != 0) {
    close(pasvSocketFd);
  }

  pasvSocketFd = socket(AF_INET, SOCK_STREAM, 0);
  if (pasvSocketFd < 0) {
      perror("Failed to create the socket.");
      return FAILURE;
    }

  struct sockaddr_in pasv_address;
  bzero(&pasv_address, sizeof(struct sockaddr_in));
  pasv_address.sin_family = AF_INET;
  pasv_address.sin_port = 0;
  
  struct ifaddrs *ifaddr, *ifa;
  struct sockaddr_in *sa;
  if (getifaddrs(&ifaddr) == -1) {
      perror("failed to getifaddrs.");
      return FAILURE;
  }

  for (ifa = ifaddr; ifa != NULL; ifa = ifa->ifa_next) {
      if(ifa->ifa_addr && ifa->ifa_addr->sa_family == AF_INET) {
        sa = (struct sockaddr_in *) ifa->ifa_addr;
        if (sa->sin_addr.s_addr != INADDR_LOOPBACK) {
          pasv_address.sin_addr.s_addr = sa->sin_addr.s_addr;
        }
      }
  }

  char buffer[1024];
  if (bind(pasvSocketFd, (const struct sockaddr*) &pasv_address, sizeof(struct sockaddr_in)) != 0) {
      perror("Failed to bind pasv socket");
      sprintf(buffer, "425 Can't open data connection.\r\n");
      sendMessage(clientId, buffer);
      return FAILURE;
  }

  if (listen(pasvSocketFd, BACKLOGSIZE) != 0) {
      perror("Failed to listen for pasv connections");
      sprintf(buffer, "425 Can't open data connection.\r\n");
      sendMessage(clientId, buffer);
      return FAILURE;
  }

  freeifaddrs(ifaddr);
  sendPasvMessageToClient(clientId, pasvSocketFd);
  return SUCCESS;
}

// mode command takes in the client id and arguement that client made
// prints out 200 if client argument is S for stream mode
// 504 for all other viable modes supported by ftp
void modeCommand(int clientId, char * argument) {
  char buffer[1024];
  if (strcasecmp(argument, "S") == 0) {
    sprintf(buffer, "200 Stream Mode Enabled.\r\n");
    sendMessage(clientId, buffer);
  } else {
    sprintf(buffer, "504 MODE command not implemented for that parameter.\r\n");
    sendMessage(clientId, buffer);
  }
}

// sends type command reply to client for ASCII and Binary mode
// otherwise sends an error message
void typeCommand(int clientId, char * argument) {
  char buffer[1024];
  if (strcasecmp(argument, "A") == 0) {
    sprintf(buffer, "200 Switching to ASCII mode.\r\n");
    sendMessage(clientId, buffer);
  } else if (strcasecmp(argument, "I") == 0) {
    sprintf(buffer, "200 Switching to Binary Mode.\r\n");
    sendMessage(clientId, buffer);
  } else {
    sprintf(buffer, "504 TYPE command not implemented for that parameter.\r\n");
    sendMessage(clientId, buffer);
  }
}

// sends str command reply to client for File mode
// otherwise sends an error message
void struCommand(int clientId, char * argument) {
  char buffer[1024];
  if (strcasecmp(argument, "F") == 0) {
    sprintf(buffer, "200 Structure set to to F.\r\n");
    sendMessage(clientId, buffer);
  } else {
    sprintf(buffer, "504 STRU command not implemented for that parameter.\r\n");
    sendMessage(clientId, buffer);
  } 
}

// accepts data connection on the pasv socket and sends the directory listings
// otherwise sends an error message to the client
void nlstCommand(int clientId) {
  char buffer[1024];
  int dataConnectionFd = 0;

  if (pasvSocketFd == 0) {
    sprintf(buffer, "425 Use PASV first.\r\n");
    sendMessage(clientId, buffer);
    return;
  }

  struct sockaddr_in clientAddress;
  socklen_t clientAddressLength = sizeof(struct sockaddr_in);

  char cwd[4096];
  if (getcwd(cwd, sizeof(cwd)) != 0) {
    sprintf(buffer, "150 Here comes the directory listing.\r\n");
    sendMessage(clientId, buffer);

    dataConnectionFd = accept(pasvSocketFd, (struct sockaddr*) &clientAddress, &clientAddressLength);
    if (dataConnectionFd == -1) {
      sprintf(buffer, "425 Cancelled Open data connection.\r\n");
      sendMessage(clientId, buffer);
      return;
    }
    
    listFiles(dataConnectionFd, ".");
    sprintf(buffer, "226 Directory Sent OK.\r\n");
    sendMessage(clientId, buffer);
  }

  close(pasvSocketFd);
  close(dataConnectionFd);
  pasvSocketFd = 0;
}

// changes the current working directory to the one given in the argument
// if directory path contains " ", ".", "..", "~" or contain "./", "../", then an error message will be sent
void cwdCommand(int clientId, char * argument) {
  char buffer[1024];
  // directory path can't be " ", ".", "..", "~" or contain "./", "../"
  if (strcmp(argument, ".") == 0 || strcmp(argument, "..") == 0 || strstr(argument, "./") || strstr(argument, "../") 
      || strcmp(argument, "~") == 0 || strcmp(argument, " ") == 0) {
    sprintf(buffer, "550 Failed to change directory.\r\n");
    sendMessage(clientId, buffer);
    return;    
  }

  if(chdir(argument) == 0) {
    sprintf(buffer, "250 Directory successfully changed.\r\n");
    sendMessage(clientId, buffer);
  } else {
    sprintf(buffer, "550 Failed to change directory.\r\n");
    sendMessage(clientId, buffer);
  }
}

// changes current working directory to the parent directory
// if directory cannot be changed then an error message is sent to the client
void cdupCommand(int clientId, char * intialWorkingDirectory) {
  char buffer[1024];
  char cwd[4096];

  if (getcwd(cwd, sizeof(cwd)) != 0) {
    if (strcmp(cwd, intialWorkingDirectory) == 0) {
      sprintf(buffer, "550 Cannot set working directory to be the parent of initial working directory.\r\n");
      sendMessage(clientId, buffer);  
    } else {
      if (chdir("..") == 0) {
        sprintf(buffer, "250 Directory successfully changed.\r\n");
        sendMessage(clientId, buffer);  
      } else {
        sprintf(buffer, "550 Failed to change directory.\r\n");
        sendMessage(clientId, buffer);
      }
    }
  } else {
    sprintf(buffer, "550 Failed to change directory.\r\n");
    sendMessage(clientId, buffer);  
  }
}

// sends a file to the client in Binary mode and sends a message if the transfer is complete
// otherwise sends an error message to the client
void retrCommand(int clientId, char * argument) {
  char buffer[1024];
  int dataConnectionFd = 0;

  if (pasvSocketFd == 0) {
    sprintf(buffer, "425 Use PASV first.\r\n");
    sendMessage(clientId, buffer);
    return;
  }

  struct sockaddr_in clientAddress;
  socklen_t clientAddressLength = sizeof(struct sockaddr_in);

  int fd = open(argument, O_RDONLY);
  if (access(argument, R_OK) == 0 && fd != -1) {
    struct stat statBuf;
    off_t offset = 0;
    int bytesSent = 0;
    fstat(fd, &statBuf);
    
    sprintf(buffer, "150 Opening BINARY mode data connection.\r\n");
    sendMessage(clientId, buffer);
    dataConnectionFd = accept(pasvSocketFd, (struct sockaddr*) &clientAddress, &clientAddressLength);

    if (dataConnectionFd == -1) {
      sprintf(buffer, "425 Cancelled Open data connection.\r\n");
      sendMessage(clientId, buffer);
      return;
    }

    bytesSent = sendfile(dataConnectionFd, fd, &offset, statBuf.st_size);
    if (bytesSent != statBuf.st_size) {
      perror("Failed to transfer all data");
      exit(EXIT_FAILURE);
    }
    sprintf(buffer, "226 Transfer complete.\r\n");
    sendMessage(clientId, buffer);
  } else {
    sprintf(buffer, "550 Failed to get file.\r\n");
    sendMessage(clientId, buffer);
  }

  close(fd);
  close(pasvSocketFd);
  close(dataConnectionFd);
  pasvSocketFd = 0;
}