from argparse import ArgumentParser
import subprocess
import time

parser = ArgumentParser('Create segments for the input video')
parser.add_argument('video', help='video filename')
parser.add_argument('total', type=int, help='integer for total frame of the video')
parser.add_argument('output', help='output path without file extension')

args = parser.parse_args()

for i in range(0, args.total):
	start = time.strftime('%H:%M:%S', time.gmtime(i))
	end = time.strftime('%H:%M:%S', time.gmtime(i+1))
	out = args.output + '_' + str(i+1) + '.flv'
	subprocess.call(['ffmpeg', '-i', args.video, '-ss', start, '-to', end, '-async', '1', out])

